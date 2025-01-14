package main

import java.io.File

import framework.igor.experiment.{ResultCache, Stage}
import model._
import task.Task

/**
 * @author jda
 */
object Load extends Stage[Config] {
  override def run(implicit config: Config, cache: ResultCache): Unit = {
    val scorer = config.scorer
    val model = GloballyNormalizedInconsistentModel

    val task = config.task(new File(config.dataDir))
    val instances = task.instances
    val trainInstances = task.trainIds.map(instances) filterNot(_.path.isEmpty)
    val testInstances = task.testIds.map(instances)

    val representations = this.task("build representations") { trainInstances.map(Annotator.annotateInstance(task)) }
    val index = this.task("build index") { FeatureIndex.buildFrom(representations) }
    val obsCache = this.task("build observations") { ObservationCache.buildFromTrainingData(representations, index) }

    cache.put('scorer, scorer)
    cache.put('model, model)
    cache.put('task, task)
    cache.put('index, index)
    cache.put('trainObsCache, obsCache)
    cache.put('trainInstances, trainInstances)
    cache.put('testInstances, testInstances)
  }
}
