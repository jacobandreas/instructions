package main

import breeze.linalg.DenseVector
import breeze.optimize._
import framework.erector.sequence.{ForwardBackward, LogHMMLattice}
import framework.igor.experiment.{ResultCache, Stage}
import model.{Model, FeatureIndex, TrainObservationCache, Scorer}
import spire.syntax.cfor._

/**
 * @author jda
 */
object Train extends Stage[Config] {

  override def run(implicit config: Config, cache: ResultCache): Unit = {
    val scorer: Scorer = cache.get('scorer)
    val model: Model = cache.get('model)
    val index: FeatureIndex = cache.get('index)
    val obsCache: TrainObservationCache = cache.get('trainObsCache)

    var params = scorer.initParams(index)
    var alignments = initAlignments(obsCache)

    cforRange (0 until config.nTrainIters) { iter =>
      params = maxParams(scorer)(params, alignments, obsCache, model)
      alignments = maxAlignments(scorer)(params, obsCache, model)
    }
  }

  def initAlignments(obsCache: TrainObservationCache): IndexedSeq[IndexedSeq[Int]] = {
    IndexedSeq.tabulate(obsCache.nExamples) { iExample =>
      IndexedSeq.tabulate(obsCache.nEvents(iExample)) { iEvent =>
        if (iEvent == obsCache.nSentences(iExample) - 1) obsCache.nEvents(iExample) - 1
        else iEvent * obsCache.nEvents(iExample) / obsCache.nSentences(iExample)
      }
    }
  }

  def maxParams(scorer: Scorer)
               (currentParams: scorer.Params,
                currentAlignments: IndexedSeq[IndexedSeq[Int]],
                obsCache: TrainObservationCache,
                model: Model)
               (implicit config: Config): scorer.Params = {
    import scorer.{zeroLike,increment,pack,unpack}

    val objective = new DiffFunction[DenseVector[Double]] {
      override def calculate(vecParams: DenseVector[Double]): (Double, DenseVector[Double]) = {
        val params = unpack(vecParams, currentParams)
        val indices = if (config.usePar) (0 until obsCache.nExamples).par else 0 until obsCache.nExamples
        val (tScore, tGrad) = indices.aggregate[(Double, scorer.Params)]((0, zeroLike(currentParams)))(
          { case ((score, grad), iExample) =>
            val eScore = model.scoreDecisions(scorer)(obsCache.pairObservations(iExample),
                                                      obsCache.altPairObservations(iExample),
                                                      obsCache.eventObservations(iExample),
                                                      obsCache.altEventObservations(iExample),
                                                      currentAlignments(iExample),
                                                      params, grad)
            (score + eScore, grad)
          },{ case ((score1, grad1), (score2, grad2)) =>
            increment(1d, grad2, grad1)
            (score1 + score2, grad1)
          }
        )
        (-tScore, -pack(tGrad))
      }
    }

    unpack(minimize(objective, currentParams), currentParams)
  }

  def maxAlignments(scorer: Scorer)
                   (currentParams: scorer.Params,
                    obsCache: TrainObservationCache,
                    model: Model): IndexedSeq[IndexedSeq[Int]] = {
    val lattice = new LogHMMLattice {
      override val numSequences: Int = obsCache.nExamples
      override def numStates(seq: Int): Int = obsCache.nEvents(seq)
      override def sequenceLength(seq: Int): Int = obsCache.nSentences(seq)

      override def nodeLogPotential(iExample: Int, iSentence: Int, iEvent: Int): Double = {
        if (iSentence == sequenceLength(iExample) - 1 && iEvent != numStates(iExample) - 1) Double.NegativeInfinity
        else model.scoreAlignment(scorer)(obsCache.pairObservations(iExample)(iEvent)(iSentence), currentParams)
      }

      override def edgeLogPotentials(iExample: Int, iEvent: Int, forward: Boolean): DenseVector[Double] = {
        val potentials = IndexedSeq.tabulate(numStates(iExample)) { iEvent2 =>
          if (forward && iEvent2 < iEvent || !forward && iEvent2 > iEvent) Double.NegativeInfinity
          else 0
        }
        DenseVector(potentials: _*)
      }
    }

    val assignments = ForwardBackward.max(lattice)
    IndexedSeq.tabulate(obsCache.nExamples)(assignments.assignment)
  }
}
