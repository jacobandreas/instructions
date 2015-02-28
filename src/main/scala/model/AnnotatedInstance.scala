package model

import epic.parser.NoParseException
import framework.arbor.syntax.{DependencyNode, DependencyStructure, ParseCollapser}
import framework.fodor.graph.EventContext
import framework.fodor.{SimpleFeature, StringFeature, IndicatorFeature}
import framework.arbor._
import framework.arbor.BerkeleyAnnotators._
import task.Task
import spire.syntax.cfor._

/**
 * @author jda
 */
case class AnnotatedInstance(wordFeats: Array[Array[Set[IndicatorFeature]]],
                             depFeats: Array[Array[Array[Set[IndicatorFeature]]]],
                             nodeFeats: Array[Array[Set[IndicatorFeature]]],
                             edgeFeats: Array[Array[Array[Set[IndicatorFeature]]]],
                             altNodeFeats: Array[Array[Array[Set[IndicatorFeature]]]],
                             altEdgeFeats: Array[Array[Array[Array[Set[IndicatorFeature]]]]])

object Annotator {
  def apply(task: Task)(instance: task.Instance): AnnotatedInstance = {

    val (wordFeats, depFeats) = instance.instructions map buildWordAndDepFeats unzip
    val (nodeFeats, edgeFeats) = instance.path map (buildNodeAndEdgeFeats(task) _ tupled) unzip

    val (altNodeFeats, altEdgeFeats) = Array.tabulate(instance.path.length) { iEvent =>
      val startState = instance.path(iEvent)._1
      val acts = task.availableActions(startState)
      val nextStates = acts map { a => task.doAction(startState, a) }
      (acts zip nextStates).map { case (a, s2) => buildNodeAndEdgeFeats(task)(startState, a, s2) }.toArray.unzip
    }.unzip

    AnnotatedInstance(wordFeats.toArray,
                      depFeats.toArray,
                      nodeFeats.toArray,
                      edgeFeats.toArray,
                      altNodeFeats.toArray,
                      altEdgeFeats.toArray)
  }

  def buildWordAndDepFeats(sent: String): (Array[Set[IndicatorFeature]], Array[Array[Set[IndicatorFeature]]]) = {
    val words = segmentWords(sent)
    val tags = tag(words)
    val deps = try {
      val tree = parse(words)
      ParseCollapser(words, tree)
    } catch {
      case e: NoParseException => DependencyStructure.linearFallback(words)
    }

    val wordFeats = Array.tabulate[Set[IndicatorFeature]](words.length) { iWord =>
      Set(StringFeature("word", words(iWord)),
           SimpleFeature(s"tag=${tags(iWord)}"))
    }
    val depFeats = Array.ofDim[Set[IndicatorFeature]](words.length, words.length)
    cforRange2 (0 until words.length, 0 until words.length) { (iWord1, iWord2) =>
      val edge = deps.labeledEdges.find(e => e._1.index == iWord1 && e._3.index == iWord2)
      if (edge.isDefined) {
        depFeats(iWord1)(iWord2) = Set(SimpleFeature(s"rel=${edge.get._2}"))
      }
    }

    (wordFeats, depFeats)
  }

  def buildNodeAndEdgeFeats(task: Task)(s1: task.State, a: task.Action, s2: task.State): (Array[Set[IndicatorFeature]], Array[Array[Set[IndicatorFeature]]]) = {
    val repr = task.represent(s1, a, s2)
    val model = repr.model
    val nodes = model.bfs(repr.event).toSeq.reverse

    val nodeFeats = Array.tabulate[Set[IndicatorFeature]](nodes.length) { iNode =>
      nodes(iNode).features.flatMap(Featurizer.discretize)
    }
    val edgeFeats = Array.ofDim[Set[IndicatorFeature]](nodes.length, nodes.length)
    cforRange2 (0 until nodes.length, 0 until nodes.length) { (iNode1, iNode2) =>
      val node1 = nodes(iNode1)
      val node2 = nodes(iNode2)
      if (iNode1 == iNode2) {
        edgeFeats(iNode1)(iNode2) = Set(new SimpleFeature("SELF"))
      } else if (model.edges.contains((node1, node2))) {
        val feats = model.labeledEdges.find(e => e._1 == node1 && e._3 == node2).get._2.features.flatMap(Featurizer.discretize)
        edgeFeats(iNode1)(iNode2) = feats
      } else {
        // TODO skip edges?
        val path = model.path(node1, node2)
        if (path.isDefined) {
          // val labels = path.get.sliding(2).map(pair => repr.model.labeledEdges.find(e => e._1 == pair(0) && e._3 == pair(1)).get._2.features).toSeq
          // assert { labels.forall(_.size == 1) }
          // val joinedLabels = labels.map(_.head.asInstanceOf[IndicatorFeature].value).mkString(",")
          // edgeFeatsNode(node1)(node2) = Array[IndicatorFeature](new SimpleFeature(s"SKIP_$joinedLabels"))
        }
      }
    }
    (nodeFeats, edgeFeats)
  }

}
