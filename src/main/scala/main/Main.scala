package main

import epic.util.{NotProvided, Optional}
import framework.fodor.IndicatorFeature
import framework.igor.experiment.{Stage, Experiment}
import model.{CompleteSparseBagScorer, CompleteSparseTreeScorer, Scorer}
import task.TaskFactory
import task.crossblock.CrossBlock
import task.hcrc.Hcrc
import task.sail.Sail

/**
 * @author jda
 */

trait DefaultConfig {
  val dataDir: String = "data"
  val usePar: Boolean = false
  val task: TaskFactory
  val scorer: Scorer = CompleteSparseTreeScorer
  val pairFeatCountCutoff: Int = 10
  val eventFeatCountCutoff: Int = 10
  val pairFeatFilter: Optional[IndicatorFeature => Boolean] = NotProvided
  val eventFeatFilter: Optional[IndicatorFeature => Boolean] = NotProvided
  val sampleAlternatives: Optional[Int] = NotProvided
  val globalAlternatives: Boolean = true
  val useL1: Boolean = false
  val regularizationStrength: Double = 1d
  val nTrainIters: Int = 5
  val nTestIters: Int = 5
  val nTestAlignmentRestarts: Int = 10
  val testLengthRangeStart: Int
  val testLengthRangeEnd: Int
  val testKnownLength: Boolean
  val testBeamSize: Int = 15
  val multiAlign: Boolean = false
  val fold: Int = -1
}

trait HcrcConfig extends DefaultConfig {
  override val task = Hcrc
  override val scorer = CompleteSparseTreeScorer
  override val pairFeatCountCutoff = 100
  override val eventFeatCountCutoff = 100
  override val pairFeatFilter: Optional[IndicatorFeature => Boolean] = Hcrc.pairFeatureFilter
  override val eventFeatFilter: Optional[IndicatorFeature => Boolean] = Hcrc.eventFeatureFilter
  override val sampleAlternatives: Optional[Int] = 20
  override val nTestIters = 2
  override val testLengthRangeStart = 15
  override val testLengthRangeEnd = 15
  override val testKnownLength = false
  override val testBeamSize = 1
}

// greedily

trait SailConfig extends DefaultConfig {
  override val task = Sail
  override val scorer = CompleteSparseTreeScorer
  override val useL1 = true
  override val globalAlternatives = false
  override val nTrainIters = 1
  override val nTestAlignmentRestarts = 1
  override val testLengthRangeStart = 1
  override val testLengthRangeEnd = 2
  override val testKnownLength = false
  override val multiAlign = true
  override val fold = 2
//  override val testBeamSize = 1
}

// 5897
// 5987
// 5984

// w/o event features:
// 5878
// 5947
// 6030

// greedily:
// 5664
// 5970
// 6030

trait CrossBlockConfig extends DefaultConfig {
  override val task = CrossBlock
  override val scorer = CompleteSparseBagScorer
  override val nTestIters = 1
//  override val pairFeatFilter: Optional[IndicatorFeature => Boolean] = (f: IndicatorFeature) => false
  override val nTestAlignmentRestarts = 10
  override val testLengthRangeStart = -1
  override val testLengthRangeEnd = -1
  override val testKnownLength = true
  override val fold = 4
  override val testBeamSize = 1
}


// w/o event features (exact, success)
// 70, 80
// 60, 100
// 90, 90
// 80, 90
// 50, 70
// TOTAL 70, 86

// final greedily (exact, success)
// 40, 50
// 80, 80
// 80, 80
// 70, 70
// 50, 50
// TOTAL 64, 66

// greedily
// 60
// 70
// 90
// 70
// 40


case class Config() extends CrossBlockConfig

object Main extends Experiment[Config] {
  override val paramManifest = manifest[Config]
  override val stages = Seq(Load, Train, Test)
}
