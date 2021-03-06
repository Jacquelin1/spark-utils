// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.spark.ml.feature

import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.feature.WoEModel.{WoEModelWriter, WoeTableWrapper}
import org.apache.spark.ml.param.shared.{HasInputCols, HasLabelCol}
import org.apache.spark.ml.param.{Param, ParamMap, Params}
import org.apache.spark.ml.util._
import org.apache.spark.ml.{Estimator, Model}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Dataset}

import scala.collection.mutable.ListBuffer

private[feature] trait WoEParams
  extends Params with HasInputCols with HasLabelCol {

  def setInputCols(values: Array[String]): this.type = set(inputCols, values)

  /**
   * Set the column name which is used as binary classes label column. The data type can be
   * boolean or numeric, which has at most two distinct values.
   *
   * @group setParam */
  def setLabelCol(value: String): this.type = set(labelCol, value)

  /**
   * The output col postfix is the text appended to the input columns when its encoded
   */
  final val outputColPostFix: Param[String] = new Param[String](this, "outputColPostFix", "The post fix to add to the input column name")

  def setOutputColPostFix(value: String): this.type = set(outputColPostFix, value)

  /** Validates and transforms the input schema. */
  protected def validateAndTransformSchema(schema: StructType): StructType = {
    require(isDefined(inputCols),
      s"WoETransformer requires input column parameter: $inputCols")
    // TODO: Val;idate output col
    //    require(isDefined(outputColPostFix),
    //      s"WoETransformer requires output column parameter: $outputCols")
    require(isDefined(labelCol),
      s"WoETransformer requires output column parameter: $labelCol")

    var schemaFields = schema.fields
    $(inputCols).toSeq.foreach(inputCol => {
      val outputCol = getOutputColName(inputCol)
      if (schema.fieldNames.contains(outputCol)) {
        throw new IllegalArgumentException(s"Output column $outputCol already exists.")
      }
      schemaFields = schemaFields :+ StructField(outputCol, DoubleType, nullable = true)
    })
    StructType(schemaFields)
  }

  def getOutputColName(inputCol: String): String = {
    s"${inputCol}_${$(outputColPostFix)}"
  }
}

/**
 * The Weight of Evidence or WoE value is a widely used measure of the "strength” of a grouping for separating
 * good and bad risk (default). It is computed from the basic odds ratio:
 *
 * (Distribution of Good Credit Outcomes) / (Distribution of Bad Credit Outcomes)
 *
 * Or the ratios of distribution "Good" / distribution "Bad" for short, where distribution refers to the proportion of Good (positive) or Bad (negative) in the
 * respective group, relative to the column totals, i.e., expressed as relative proportions of the total number of Good and Bad.
 *
 * In addition, the information value or IV can be computed based on WoE.  It expresses the amount of diagnostic information of a predictor variable for separating
 * the Good from the Bad. Low values mean the WoE can simply be ignored (ie. <0.02)
 *
 * See https://documentation.statsoft.com/STATISTICAHelp.aspx?path=WeightofEvidence/WeightofEvidenceWoEIntroductoryOverview
 *
 * The only differences from the above outlines algorithm is that we do not multiply by 100. This is consistent with the Python package we use from XAM.
 *
 * TODO: Remove internal APIs so it can sit outside the org.apache package
 * TODO: Handle out of fold encoding
 * TODO: Handle multi column
 */
@Experimental
@Since("2.4.0")
class WoETransformer(override val uid: String)
  extends Estimator[WoEModel] with WoEParams with DefaultParamsWritable {

  def this() = this(Identifiable.randomUID("woe"))


  override def fit(dataset: Dataset[_]): WoEModel = {
    transformSchema(dataset.schema, logging = true)
    var tableList = new ListBuffer[WoeTableWrapper]()
    $(inputCols).toSeq.foreach(inputCol => {
      val table = WoETransformer.getWoeTable(dataset, inputCol, $(labelCol))
      val outputCol = getOutputColName(inputCol)
      tableList += WoeTableWrapper(inputCol, outputCol, table)
    })

    copyValues(new WoEModel(uid, tableList).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  override def copy(extra: ParamMap): WoETransformer = defaultCopy(extra)
}

@Experimental
@Since("2.4.0")
object WoETransformer {

  def getInformationValue(dataset: DataFrame, categoryCol: String, labelCol: String): Double = {
    val tt = getWoeTable(dataset, categoryCol, labelCol)
    val iv = tt.selectExpr("SUM(woe * (p1 - p0)) as iv").first().getAs[Double](0)
    iv
  }

  private def getWoeTable(dataset: Dataset[_], categoryCol: String, labelCol: String): DataFrame = {
    val data = dataset.select(categoryCol, labelCol)
    val tmpTableName = "woe_temp"
    data.createOrReplaceTempView(tmpTableName)
    val err = 0.01
    val query =
      s"""
         |SELECT
         |$categoryCol,
         |SUM (IF(CAST ($labelCol AS DOUBLE)=1, 1, 0)) AS 1count,
         |SUM (IF(CAST ($labelCol AS DOUBLE)=0, 1, 0)) AS 0count
         |FROM $tmpTableName
         |GROUP BY $categoryCol
        """.stripMargin
    val groupResult = data.sqlContext.sql(query)

    val total0 = groupResult.selectExpr("SUM(0count)").first().getAs[Long](0).toDouble
    val total1 = groupResult.selectExpr("SUM(1count)").first().getAs[Long](0).toDouble
    groupResult.selectExpr(
      categoryCol,
      s"1count/$total1 AS p1",
      s"0count/$total0 AS p0",
      s"LOG(($err + 1count) / $total1 * $total0 / (0count + $err)) AS woe"
    )
  }
}

@Experimental
@Since("2.4.0")
class WoEModel private[ml](override val uid: String,
                           val woeTableWrappers: Seq[WoeTableWrapper])
  extends Model[WoEModel] with WoEParams with MLWritable {

  override def transform(dataset: Dataset[_]): DataFrame = {
    var processedDataSet: DataFrame = dataset.toDF()
    woeTableWrappers.foreach(woeTableWrapper => {
      val woeTable = woeTableWrapper.woeTable
      val inputCol = woeTableWrapper.inputCol
      val outputCol = woeTableWrapper.outputCol

      val iv = woeTable.selectExpr("SUM(woe * (p1 - p0)) as iv").first().getAs[Double](0)
      logInfo(s"iv value for $inputCol is: $iv")

      val woeMap = woeTable.rdd.map(r => {
        val category = r.get(0)
        val woe = r.getAs[Double]("woe")
        (category, woe)
      }).collectAsMap()

      val trans = udf { factor: Any => woeMap.get(factor) }
      processedDataSet = processedDataSet.withColumn(outputCol, trans(col(inputCol)))
    })
    processedDataSet
  }

  @Since("2.4.0")
  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  @Since("2.4.0")
  override def copy(extra: ParamMap): WoEModel = {
    val copied = new WoEModel(uid, woeTableWrappers)
    copyValues(copied, extra).setParent(parent)
  }

  @Since("2.4.0")
  override def write: MLWriter = new WoEModelWriter(this)
}


@Since("2.4.0")
object WoEModel extends MLReadable[WoEModel] {

  private[WoEModel]
  class WoEModelWriter(instance: WoEModel) extends MLWriter {

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val wrappers = instance.woeTableWrappers
      wrappers.foreach(woeWrapperTable => {
        val dataPath = new Path(path, s"data_${woeWrapperTable.inputCol}").toString
        woeWrapperTable.woeTable.repartition(1).write.parquet(dataPath)
      })
    }
  }

  private class WoEModelReader extends MLReader[WoEModel] {

    private val className = classOf[WoEModel].getName

    override def load(path: String): WoEModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)

      //      val dataPath = new Path(path, "data").toString
      //      val data = sqlContext.read.parquet(dataPath)
      //      val model = new WoEModel(metadata.uid, data)
      //      metadata.getAndSetParams(model)
      //      model
      ??? //TODO
    }
  }

  @Since("2.4.0")
  override def read: MLReader[WoEModel] = new WoEModelReader

  @Since("2.4.0")
  override def load(path: String): WoEModel = super.load(path)

  case class WoeTableWrapper(inputCol: String, outputCol: String, woeTable: DataFrame)
}