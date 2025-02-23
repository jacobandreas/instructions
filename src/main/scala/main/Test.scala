package main

import breeze.linalg.DenseVector
import breeze.stats.distributions.Rand
import framework.erector.sequence.{ForwardBackward, LogHMMLattice}
import framework.erector.util.ViterbiBeam
import framework.erector.util.learn.{ClassificationResults, LogisticRegression}
import framework.fodor.StringFeature
import framework.igor.eval.EvalStats
import framework.igor.experiment.{ResultCache, Stage}
import model._
import task.hcrc.{HcrcAction, HcrcState}
import task.{TaskInstance, Task}
import spire.syntax.cfor._

/**
 * @author jda
 */
object Test extends Stage[Config] {

  override def run(implicit config: Config, cache: ResultCache): Unit = {
    val scorer: Scorer = cache.get('scorer)
    val model: Model = cache.get('model)
    val task: Task = cache.get('task)
    val index: FeatureIndex = cache.get('index)
    val testInstances = cache.get('testInstances).asInstanceOf[IndexedSeq[task.Instance]]
    val lengthModel: ClassificationResults = cache.get('lengthModel)
    val lengthFeaturizer: (AnnotatedWalkthrough => DenseVector[Double]) = cache.get('lengthFeaturizer)
    val params: scorer.Params = cache.get('params)

    dumpParams(params.asInstanceOf[SparseParams], index)

    val predictions = testInstances.toIterator.map { inst =>
      val pred = this.task(inst.instructions.head) { predict(scorer, task)(inst, params, model, index, lengthModel, lengthFeaturizer) }
      pred
    }
    val golds = testInstances.map(_.path).toIterator
    val scores = predictions zip golds map { case (pred, gold) =>
      val score = task.score(pred, gold)
      logger.info(score.toString)
      logger.info("")
      score
    }
    val finalScore = scores.reduce(_ + _)
    logger.info(finalScore.toString)
  }

  def predict(scorer: Scorer,
              task: Task)
             (instance: task.Instance,
              params: scorer.Params,
              model: Model,
              index: FeatureIndex,
              lengthModel: ClassificationResults,
              lengthFeaturizer: (AnnotatedWalkthrough => DenseVector[Double]))
             (implicit config: Config): IndexedSeq[(task.State, task.Action, task.State)] = {
    if (instance.path.isEmpty) return IndexedSeq()
    val startState = instance.path.head._1
    val annotatedWalkthrough = Annotator.annotateWalkthrough(instance.instructions)

//    println(instance.instructions.mkString("\n"))

    val (lengthStart, lengthEnd) =
      if (config.testKnownLength) (instance.path.length, instance.path.length)
      else (config.testLengthRangeStart, config.testLengthRangeEnd)
    val (prediction, score) = (lengthStart to lengthEnd).flatMap { pathLength =>
      logger.info(s"length $pathLength")
      val lengthScore = lengthModel.score(lengthFeaturizer(annotatedWalkthrough), pathLength)
//      val lengthScore = if (pathLength == instance.path.length) 0 else Double.NegativeInfinity
      Seq.fill(config.nTestAlignmentRestarts)(randAlignment(instance.instructions.length, pathLength)).map { initAlignment =>
        var alignment = initAlignment
//        println(alignment)
        var path = null: IndexedSeq[(task.State, task.Action, task.State)]
        var score = 0d
        cforRange (0 until config.nTestIters) { iter =>
//          println(alignment)
          path = maxPath(scorer, task)(startState, pathLength, alignment, annotatedWalkthrough, params, model, index)._1
          if (path.nonEmpty) {
            val (iAlignment, iScore) = maxAlignment(scorer, task)(path, annotatedWalkthrough, params, model, index)
            alignment = iAlignment
            score = iScore + lengthScore
          }
//          println(alignment)
//          task.visualize(path, instance.path)
        }
        logger.info(s"$score, $path")
        logger.info(task.score(path, instance.path).toString)
//        task.visualize(path, instance.path)
//        System.in.read()
//        System.exit(1)
        (path, score)
      }
    }.maxBy(_._2)
//    System.exit(1)
//    task.visualize(prediction, instance.path)
    prediction
  }

  def randAlignment(nSentences: Int, nEvents: Int)(implicit config: Config): IndexedSeq[Int] = {
    (Rand.randInt(nEvents).sample(nSentences - 1) :+ (nEvents - 1)).sorted
  }

  def maxPath(scorer: Scorer,
              task: Task)
             (startState: task.State,
              pathLength: Int,
              alignment: IndexedSeq[Int],
              annotatedWalkthrough: AnnotatedWalkthrough,
              params: scorer.Params,
              model: Model,
              index: FeatureIndex)
             (implicit config: Config): (IndexedSeq[(task.State, task.Action, task.State)], Double) = {
    sealed trait AHypothesis { val state: task.State; val score: Double }
    case class StartHypothesis(state: task.State) extends AHypothesis { override val score = 0d }
    case class Hypothesis(state: task.State, action: task.Action, parent: AHypothesis, score: Double) extends AHypothesis

    var lastHyps = IndexedSeq[AHypothesis](StartHypothesis(startState))
    (0 until pathLength).foreach { t =>
      val beam = new ViterbiBeam[AHypothesis, Double, task.State](config.testBeamSize)(_.score, _.state)
      val alignedSentences: IndexedSeq[Int] =
        if (config.multiAlign) 0 until alignment.length
        else alignment.zipWithIndex.filter(_._1 == t).map(_._2).toArray
//      System.out.println("Sentences here:")
//      alignedSentences.map(annotatedWalkthrough.wordFeats(_).flatMap(_.map(_.value)).mkString(" ")).foreach(println)
      lastHyps.foreach { lastHyp =>
        val acts = task.availableActions(lastHyp.state).toSeq
        if (acts.nonEmpty) {
          task.availableActions(lastHyp.state).foreach { nextAction =>
            val nextState = task.doAction(lastHyp.state, nextAction)
            val (pairObservations, eventObservation) = buildObservations(task)(lastHyp.state, nextAction, nextState, annotatedWalkthrough, alignedSentences, index)
            val score = model.scoreOneDecision(scorer)(pairObservations, eventObservation, params, null.asInstanceOf[scorer.Params])
            val nextHyp = Hypothesis(nextState, nextAction, lastHyp, lastHyp.score + score)
//            println("consider " + score + ": " + nextAction)
            beam.add(nextHyp)
          }
        }
      }
      val result = beam.result()
//      println("Beam:")
//      result.sortBy(_.score).foreach(h => println(h.score, h.state.asInstanceOf[HcrcState].landmark))
//      println("---")
      if (result.nonEmpty) {
        lastHyps = result
      }
    }
//    println("===")

    def unwind(hyp: AHypothesis): IndexedSeq[(task.State,task.Action,task.State)] = {
      hyp match {
        case h:Hypothesis => unwind(h.parent) :+ (h.parent.state, h.action, h.state)
        case h:StartHypothesis => IndexedSeq()
      }
    }

    if (lastHyps.isEmpty) {
      (IndexedSeq(), Double.NegativeInfinity)
    } else {
      val bestLast = lastHyps.maxBy(_.score)
      val unwound = unwind(lastHyps.maxBy(_.score))
      (unwound, bestLast.score)
    }
  }

  def maxAlignment(scorer: Scorer,
                   task: Task)
                  (path: IndexedSeq[(task.State, task.Action, task.State)],
                   annotatedWalkthrough: AnnotatedWalkthrough,
                   params: scorer.Params,
                   model: Model,
                   index: FeatureIndex)
                  (implicit config: Config): (IndexedSeq[Int], Double) = {

    val annotatedEvents = path map (Annotator.annotateEvent(task) _ tupled)
    val lattice = new LogHMMLattice {
      override def numSequences: Int = 1
      override def sequenceLength(seq: Int): Int = annotatedWalkthrough.wordFeats.length
      override def numStates(seq: Int): Int = path.length
      override def edgeLogPotentials(seq: Int, iEvent: Int, forward: Boolean): DenseVector[Double] = {
        val vals = (0 until numStates(seq)).map { iEvent2 =>
          if (forward && iEvent2 < iEvent || !forward && iEvent2 > iEvent) Double.NegativeInfinity
//          else if (forward && iEvent2 > iEvent + 1 || !forward && iEvent2 < iEvent - 1) Double.NegativeInfinity
          else 0
        }
        DenseVector[Double](vals:_*)
      }
      override def nodeLogPotential(seq: Int, iSentence: Int, iEvent: Int): Double = {
        if (iSentence == sequenceLength(0) - 1 && iEvent != numStates(0) - 1) return Double.NegativeInfinity
        if (!config.multiAlign && iSentence == 0 && iEvent != 0) return Double.NegativeInfinity

        val wordFeats = annotatedWalkthrough.wordFeats(iSentence)
        val nodeFeats = annotatedEvents(iEvent).nodeFeats
        val visitOrder = annotatedWalkthrough.visitOrders(iSentence)
        val edgeFeats = annotatedEvents(iEvent).edgeFeats
        val depFeats = annotatedWalkthrough.depFeats(iSentence)
        val obs = ObservationCache.buildPairObservation(nodeFeats, wordFeats, visitOrder, edgeFeats, depFeats, index, shortCircuit = false)
        val score = model.scoreAlignment(scorer)(obs, params)
        score
      }
    }

    val result = ForwardBackward.max(lattice)
    (result.assignment(0), result.seqScore(0))
  }

  def buildObservations(task: Task)
                       (s1: task.State,
                        a: task.Action,
                        s2: task.State,
                        annotatedWalkthrough: AnnotatedWalkthrough,
                        alignedSentences: IndexedSeq[Int],
                        index: FeatureIndex): (Array[PairObservation], EventObservation) = {
    val annotatedEvent = Annotator.annotateEvent(task)(s1, a, s2)
    ObservationCache.build(annotatedEvent, annotatedWalkthrough, alignedSentences, index)
  }

  def dumpParams(params: SparseParams, index: FeatureIndex): Unit = {
    index.pair.pairs.map { case (feat, idx) =>
      val weight = params.sparsePair(idx)
      (weight, s"$feat\t$weight")
    }.toSeq.sortBy(_._1).foreach(p => logger.info(p._2))
    index.event.pairs.map { case (feat, idx) =>
      val weight = params.sparseEvent(idx)
      (weight, s"$feat\t$weight")
    }.toSeq.sortBy(_._1).foreach(p => logger.info(p._2))
  }

}
