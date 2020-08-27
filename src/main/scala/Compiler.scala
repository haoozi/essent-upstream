package essent

import collection.mutable.HashMap
import java.io.Writer

import essent.Emitter._
import essent.Extract._
import essent.ir._
import essent.Util._

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.options.Dependency
import firrtl.Mappers._
import firrtl.PrimOps._
import firrtl.stage.TransformManager.TransformDependency
import firrtl.Utils._


class CppEmitter(initialOpt: OptFlags, writer: Writer) extends firrtl.SeqTransform with firrtl.Emitter {
  def inputForm = LowForm
  def outputForm = LowForm

  override def prerequisites = ESSENTForms.ReadyToEmit

  val outputSuffix: String = ".h"

  val tabs = "  "
  val flagVarName = "PARTflags"
  val actVarName = "ACTcounts"
  val sigTrackName = "SIGcounts"
  val sigActName = "SIGact"
  val sigExtName = "SIGext"
  var sigNameToID = Map[String,Int]()

  implicit val rn = new Renamer

  // Writing To File
  //----------------------------------------------------------------------------
  def writeLines(indentLevel: Int, lines: String) {
    writeLines(indentLevel, Seq(lines))
  }

  def writeLines(indentLevel: Int, lines: Seq[String]) {
    lines foreach { s => writer write tabs*indentLevel + s + "\n" }
  }


  // Declaring Modules
  //----------------------------------------------------------------------------
  def declareModule(m: Module, topName: String) {
    val registers = findInstancesOf[DefRegister](m.body)
    val memories = findInstancesOf[DefMemory](m.body)
    val registerDecs = registers flatMap {d: DefRegister => {
      val typeStr = genCppType(d.tpe)
      val regName = d.name
      Seq(s"$typeStr $regName;")
    }}
    val memDecs = memories map {m: DefMemory => {
      s"${genCppType(m.dataType)} ${m.name}[${m.depth}];"
    }}
    val modulesAndPrefixes = findModuleInstances(m.body)
    val moduleDecs = modulesAndPrefixes map { case (module, fullName) => {
      val instanceName = fullName.split("\\.").last
      s"$module $instanceName;"
    }}
    val modName = m.name
    writeLines(0, "")
    writeLines(0, s"typedef struct $modName {")
    writeLines(1, registerDecs)
    writeLines(1, memDecs)
    writeLines(1, m.ports flatMap emitPort(modName == topName))
    writeLines(1, moduleDecs)
    writeLines(0, "")
    writeLines(1, s"$modName() {")
    writeLines(2, initializeVals(modName == topName)(m, registers, memories))
    writeLines(1, "}")
    if (modName == topName) {
      writeLines(0, "")
      // writeLines(1, s"void connect_harness(CommWrapper<struct $modName> *comm);")
    } else {
      writeLines(0, s"} $modName;")
    }
  }

  def declareExtModule(m: ExtModule) {
    val modName = m.name
    writeLines(0, "")
    writeLines(0, s"typedef struct $modName {")
    writeLines(1, m.ports flatMap emitPort(true))
    writeLines(0, s"} $modName;")
  }


  // Write General-purpose Eval
  //----------------------------------------------------------------------------
  // TODO: move specialized CondMux emitter elsewhere?
  def writeBodyInner(indentLevel: Int, ng: NamedGraph, opt: OptFlags,
                     keepAvail: Set[String] = Set()) {
    // ng.stmtsOrdered foreach { stmt => writeLines(indentLevel, emitStmt(stmt)) }
    if (opt.conditionalMuxes)
      MakeCondMux(ng, rn, keepAvail)
    val noMoreMuxOpts = opt.copy(conditionalMuxes = false)
    ng.stmtsOrdered foreach { stmt => stmt match {
      case cm: CondMux => {
        if (rn.nameToMeta(cm.name).decType == MuxOut)
          writeLines(indentLevel, s"${genCppType(cm.mux.tpe)} ${rn.emit(cm.name)};")
        val muxCondRaw = emitExpr(cm.mux.cond)
        val muxCond = if (muxCondRaw == "reset") s"UNLIKELY($muxCondRaw)" else muxCondRaw
        writeLines(indentLevel, s"if ($muxCond) {")
        writeBodyInner(indentLevel + 1, NamedGraph(cm.tWay), noMoreMuxOpts)
        writeLines(indentLevel, "} else {")
        writeBodyInner(indentLevel + 1, NamedGraph(cm.fWay), noMoreMuxOpts)
        writeLines(indentLevel, "}")
      }
      case _ => {
        writeLines(indentLevel, emitStmt(stmt))
        if (opt.trackSigs) emitSigTracker(stmt, indentLevel, opt)
      }
    }}
  }

  def writeRegResetOverrides(ng: NamedGraph) {
    val updatesWithResets = ng.allRegDefs filter { r => emitExpr(r.reset) != "UInt<1>(0x0)" }
    val resetGroups = updatesWithResets.groupBy(r => emitExpr(r.reset))
    val overridesToWrite = resetGroups.toSeq flatMap {
      case (resetName, regDefs) => {
        val body = regDefs map {
          r => s"$tabs${rn.emit(r.name)} = ${emitExpr(r.init)};"
        }
        Seq(s"if ($resetName) {") ++ body ++ Seq("}")
      }
    }
    if (overridesToWrite.nonEmpty) {
      writeLines(2, "if (update_registers) {")
      // FUTURE: will overrides need triggers if partitioned?
      writeLines(3, overridesToWrite)
      writeLines(2, "}")
    }
  }


  // Write Zoning Optimized Eval
  //----------------------------------------------------------------------------
  def genEvalFuncName(partID: Int): String = "EVAL_" + partID

  def genDepPartTriggers(consumerIDs: Seq[Int], condition: String): Seq[String] = {
    consumerIDs.sorted map { consumerID => s"$flagVarName[$consumerID] |= $condition;" }
  }

  def genAllTriggers(signalNames: Seq[String], outputConsumers: Map[String, Seq[Int]],
      suffix: String): Seq[String] = {
    selectFromMap(signalNames, outputConsumers).toSeq flatMap { case (name, consumerIDs) => {
      genDepPartTriggers(consumerIDs, s"${rn.emit(name)} != ${rn.emit(name + suffix)}")
    }}
  }

  def writeZoningPredecs(
      ng: NamedGraph,
      condPartWorker: MakeCondPart,
      topName: String,
      extIOtypes: Map[String, Type],
      opt: OptFlags) {
    // predeclare part outputs
    val outputPairs = condPartWorker.getPartOutputsToDeclare()
    val outputConsumers = condPartWorker.getPartInputMap()
    writeLines(1, outputPairs map {case (name, tpe) => s"${genCppType(tpe)} ${rn.emit(name)};"})
    val extIOCacheDecs = condPartWorker.getExternalPartInputTypes(extIOtypes) map {
      case (name, tpe) => s"${genCppType(tpe)} ${rn.emit(name + condPartWorker.cacheSuffix)};"
    }
    writeLines(1, extIOCacheDecs)
    writeLines(1, s"std::array<bool,${condPartWorker.getNumParts()}> $flagVarName;")
    // FUTURE: worry about namespace collisions with user variables
    writeLines(1, s"bool sim_cached = false;")
    writeLines(1, s"bool regs_set = false;")
    writeLines(1, s"bool update_registers;")
    writeLines(1, s"bool done_reset;")
    writeLines(1, s"bool verbose;")
    writeLines(0, "")
    ng.stmtsOrdered foreach { stmt => stmt match {
      case cp: CondPart => {
        writeLines(1, s"void ${genEvalFuncName(cp.id)}() {")
        if (!cp.alwaysActive)
          writeLines(2, s"$flagVarName[${cp.id}] = false;")
        if (opt.trackParts)
          writeLines(2, s"$actVarName[${cp.id}]++;")
        val cacheOldOutputs = cp.outputsToDeclare.toSeq map {
          case (name, tpe) => { s"${genCppType(tpe)} ${rn.emit(name + condPartWorker.cacheSuffix)} = ${rn.emit(name)};"
        }}
        writeLines(2, cacheOldOutputs)
        val (regUpdates, noRegUpdates) = partitionByType[RegUpdate](cp.memberStmts)
        val keepAvail = (cp.outputsToDeclare map { _._1 }).toSet
        writeBodyInner(2, NamedGraph(noRegUpdates), opt, keepAvail)
        writeLines(2, genAllTriggers(cp.outputsToDeclare.keys.toSeq, outputConsumers, condPartWorker.cacheSuffix))
        val regUpdateNamesInPart = regUpdates flatMap findResultName
        writeLines(2, genAllTriggers(regUpdateNamesInPart, outputConsumers, "$next"))
        writeLines(2, regUpdates flatMap emitStmt)
        // triggers for MemWrites
        val memWritesInPart = cp.memberStmts collect { case mw: MemWrite => mw }
        val memWriteTriggers = memWritesInPart flatMap { mw => {
          val condition = s"${emitExprWrap(mw.wrEn)} && ${emitExprWrap(mw.wrMask)}"
          genDepPartTriggers(outputConsumers.getOrElse(mw.memName, Seq()), condition)
        }}
        writeLines(2, memWriteTriggers)
        writeLines(1, "}")
      }
      case _ => throw new Exception(s"Statement at top-level is not a CondPart (${stmt.serialize})")
    }}
    writeLines(0, "")
  }

  def writeZoningBody(ng: NamedGraph, condPartWorker: MakeCondPart, opt: OptFlags) {
    writeLines(2, "if (reset || !done_reset) {")
    writeLines(3, "sim_cached = false;")
    writeLines(3, "regs_set = false;")
    writeLines(2, "}")
    writeLines(2, "if (!sim_cached) {")
    writeLines(3, s"$flagVarName.fill(true);")
    writeLines(2, "}")
    writeLines(2, "sim_cached = regs_set;")
    writeLines(2, "this->update_registers = update_registers;")
    writeLines(2, "this->done_reset = done_reset;")
    writeLines(2, "this->verbose = verbose;")
    val outputConsumers = condPartWorker.getPartInputMap()
    val externalPartInputNames = condPartWorker.getExternalPartInputNames()
    // do activity detection on other inputs (external IOs and resets)
    writeLines(2, genAllTriggers(externalPartInputNames, outputConsumers, condPartWorker.cacheSuffix))
    // cache old versions
    val extIOCaches = externalPartInputNames map {
      sigName => s"${rn.emit(sigName + condPartWorker.cacheSuffix)} = ${rn.emit(sigName)};"
    }
    writeLines(2, extIOCaches.toSeq)
    ng.stmtsOrdered foreach { stmt => stmt match {
      case cp: CondPart => {
        if (!cp.alwaysActive)
          writeLines(2, s"if ($flagVarName[${cp.id}]) ${genEvalFuncName(cp.id)}();")
        else
          writeLines(2, s"${genEvalFuncName(cp.id)}();")
      }
      case _ => writeLines(2, emitStmt(stmt))
    }}
    // writeLines(2,  "#ifdef ALL_ON")
    // writeLines(2, s"$flagVarName.fill(true);" )
    // writeLines(2,  "#endif")
    writeLines(2, "regs_set = true;")
  }


  def declareSigTracking(ng: NamedGraph, topName: String, opt: OptFlags) {
    val allNamesAndTypes = ng.collectValidStmts(ng.nodeRange) flatMap findStmtNameAndType
    sigNameToID = (allNamesAndTypes map { _._1 }).zipWithIndex.toMap
    writeLines(0, "")
    writeLines(0, s"std::array<uint64_t,${sigNameToID.size}> $sigTrackName{};")
    if (opt.trackExts) {
      writeLines(0, s"std::array<bool,${sigNameToID.size}> $sigActName{};")
      writeLines(0, s"std::array<uint64_t,${sigNameToID.size}> $sigExtName{};")
    }
    writeLines(0, "namespace old {")
    writeLines(1, allNamesAndTypes map {
      case (name, tpe) => s"${genCppType(tpe)} ${name.replace('.','$')};"
    })
    writeLines(0, "}")
  }

  def emitSigTracker(stmt: Statement, indentLevel: Int, opt: OptFlags) {
    stmt match {
      case mw: MemWrite =>
      case _ => {
        val resultName = findResultName(stmt)
        resultName match {
          case Some(name) => {
            val cleanName = name.replace('.','$')
            writeLines(indentLevel, s"$sigTrackName[${sigNameToID(name)}] += $name != old::$cleanName ? 1 : 0;")
            if (opt.trackExts) {
              writeLines(indentLevel, s"$sigActName[${sigNameToID(name)}] = $name != old::$cleanName;")
              val depNames = findDependencesStmt(stmt).head.deps
              val trackedDepNames = depNames filter sigNameToID.contains
              val depTrackers = trackedDepNames map {name => s"$sigActName[${sigNameToID(name)}]"}
              val anyDepActive = depTrackers.mkString(" || ")
              if (anyDepActive.nonEmpty)
                writeLines(indentLevel, s"$sigExtName[${sigNameToID(name)}] += !$sigActName[${sigNameToID(name)}] && ($anyDepActive) ? 1 : 0;")
            }
            writeLines(indentLevel, s"old::$cleanName = $name;")
          }
          case None =>
        }
      }
    }
  }

  def emitJsonWriter(opt: OptFlags, numParts: Int) {
    writeLines(0, "void writeActToJson() {")
    writeLines(1, "std::fstream file(\"activities.json\", std::ios::out | std::ios::binary);")
    writeLines(1, "JSON all_data;")
    if (opt.trackSigs) {
      writeLines(1, "JSON sig_acts;")
      writeLines(1, s"for (int i=0; i<${sigNameToID.size}; i++) {")
      writeLines(2, s"""sig_acts[i] = JSON({"id", i, "acts", $sigTrackName[i]});""")
      writeLines(1, "}")
      writeLines(1, "all_data[\"signal-activities\"] = sig_acts;")
    }
    if (opt.trackParts) {
      writeLines(1, "JSON part_acts;")
      writeLines(1, s"for (int i=0; i<$numParts; i++) {")
      writeLines(2, s"""part_acts[i] = JSON({"id", i, "acts", $actVarName[i]});""")
      writeLines(1, "}")
      writeLines(1, "all_data[\"part-activities\"] = part_acts;")
    }
    if (opt.trackExts) {
      writeLines(1, "JSON sig_exts;")
      writeLines(1, s"for (int i=0; i<${sigNameToID.size}; i++) {")
      writeLines(2, s"""sig_exts[i] = JSON({"id", i, "exts", $sigExtName[i]});""")
      writeLines(1, "}")
      writeLines(1, "all_data[\"sig-extinguishes\"] = sig_exts;")
    }
    writeLines(1, "all_data[\"cycles\"] = cycle_count;")
    writeLines(1, "file << all_data << std::endl;")
    writeLines(1, "file.close();")
    writeLines(0, "}")
  }


  // General Structure (and Compiler Boilerplate)
  //----------------------------------------------------------------------------
  def emit(state: firrtl.CircuitState, writer: java.io.Writer) {}
  // TODO: unimplemented, but also deprecated in firrtl

  def transforms = new firrtl.stage.TransformManager(
    ESSENTForms.ReadyToEmit, prerequisites).flattenedTransformOrder

  override def execute(state: CircuitState): CircuitState = {
    val opt = initialOpt
    val circuit = state.circuit
    val topName = circuit.main
    val headerGuardName = topName.toUpperCase + "_H_"
    writeLines(0, s"#ifndef $headerGuardName")
    writeLines(0, s"#define $headerGuardName")
    writeLines(0, "")
    writeLines(0, "#include <array>")
    writeLines(0, "#include <cstdint>")
    writeLines(0, "#include <cstdlib>")
    writeLines(0, "#include <uint.h>")
    writeLines(0, "#include <sint.h>")
    writeLines(0, "#define UNLIKELY(condition) __builtin_expect(static_cast<bool>(condition), 0)")
    if (opt.trackParts || opt.trackSigs) {
      writeLines(0, "#include <fstream>")
      writeLines(0, "#include \"../SimpleJSON/json.hpp\"")
      writeLines(0, "using json::JSON;")
      writeLines(0, "uint64_t cycle_count = 0;")
    }
    val ng = NamedGraph(circuit, opt.removeFlatConnects)
    val containsAsserts = ng.containsStmtOfType[Stop]()
    val extIOMap = findExternalPorts(circuit)
    val condPartWorker = MakeCondPart(ng, rn, extIOMap)
    rn.populateFromNG(ng, extIOMap)
    if (opt.useCondParts) {
      condPartWorker.doOpt(opt.partCutoff)
    } else {
      if (opt.regUpdates)
        OptElideRegUpdates(ng)
    }
    // if (opt.trackSigs)
    //   declareSigTracking(sg, topName, opt)
    // if (opt.trackParts)
    //   writeLines(1, s"std::array<uint64_t,${sg.getNumParts()}> $actVarName{};")
    // if (opt.trackParts || opt.trackSigs)
    //   emitJsonWriter(opt, condPartWorker.getNumParts())
    // if (opt.partStats)
    //   sg.dumpPartInfoToJson(opt, sigNameToID)
    // if (opt.trackExts)
    //   sg.dumpNodeTypeToJson(sigNameToID)
    // sg.reachableAfter(sigNameToID)
    circuit.modules foreach {
      case m: Module => declareModule(m, topName)
      case m: ExtModule => declareExtModule(m)
    }
    val topModule = findModule(topName, circuit) match {case m: Module => m}
    if (initialOpt.writeHarness) {
      writeLines(0, "")
      writeLines(1, s"void connect_harness(CommWrapper<struct $topName> *comm) {")
      writeLines(2, HarnessGenerator.harnessConnections(topModule))
      writeLines(1, "}")
      writeLines(0, "")
    }
    if (containsAsserts) {
      writeLines(1, "bool assert_triggered = false;")
      writeLines(1, "int assert_exit_code;")
      writeLines(0, "")
    }
    if (opt.useCondParts)
      writeZoningPredecs(ng, condPartWorker, circuit.main, extIOMap, opt)
    writeLines(1, s"void eval(bool update_registers, bool verbose, bool done_reset) {")
    if (opt.trackParts || opt.trackSigs)
      writeLines(2, "cycle_count++;")
    if (opt.useCondParts)
      writeZoningBody(ng, condPartWorker, opt)
    else
      writeBodyInner(2, ng, opt)
    if (containsAsserts)
      writeLines(2, "if (done_reset && update_registers && assert_triggered) exit(assert_exit_code);")
    writeRegResetOverrides(ng)
    writeLines(1, "}")
    // if (opt.trackParts || opt.trackSigs) {
    //   writeLines(1, s"~$topName() {")
    //   writeLines(2, "writeActToJson();")
    //   writeLines(1, "}")
    // }
    writeLines(0, s"} $topName;") //closing top module dec
    writeLines(0, "")
    writeLines(0, s"#endif  // $headerGuardName")
    state
  }
}


// TODO: use functionality within newer firrtl
class DumpLowFIRRTL(loFirWriter: Option[Writer]) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState): CircuitState = {
    loFirWriter foreach { _.write(state.circuit.serialize) }
    state
  }
}

object ESSENTForms {
  val ReadyToEmit: Seq[TransformDependency] =
    firrtl.stage.Forms.LowFormOptimized ++
    Seq(
      Dependency(essent.passes.ReplaceAsyncRegs),
      Dependency(essent.passes.NoClockConnects),
      Dependency(essent.passes.RegFromMem1),
      Dependency(essent.passes.FactorMemReads),
      Dependency(essent.passes.FactorMemWrites),
      Dependency(essent.passes.SplitRegUpdates),
      Dependency(essent.passes.FixMulResultWidth),
      // Dependency(essent.passes.FixSubType),
      Dependency(essent.passes.DistinctTypeInstNames)
    )
}

class CCCompiler(opt: OptFlags, writer: Writer, loFirWriter: Option[Writer]) extends Compiler {
  def emitter = new CppEmitter(opt, writer)
  def transforms: Seq[Transform] = ESSENTForms.ReadyToEmit.map(_.getObject)
}
