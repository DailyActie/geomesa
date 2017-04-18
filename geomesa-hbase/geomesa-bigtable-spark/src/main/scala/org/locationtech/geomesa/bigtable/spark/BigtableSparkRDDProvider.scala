package org.locationtech.geomesa.bigtable.spark

import java.io.ByteArrayOutputStream
import java.util.Base64

import com.google.bigtable.v2.RowSet
import com.google.cloud.bigtable.hbase.BigtableExtendedScan
import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce._
import org.apache.spark.SparkContext
import org.geotools.data.{DataStoreFinder, Query}
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.bigtable.data.BigtableDataStoreFactory
import org.locationtech.geomesa.hbase.data.{EmptyPlan, HBaseDataStore}
import org.locationtech.geomesa.hbase.index.HBaseFeatureIndex
import org.locationtech.geomesa.jobs.GeoMesaConfigurator
import org.locationtech.geomesa.spark.SpatialRDD
import org.locationtech.geomesa.spark.hbase.{HBaseGeoMesaRecordReader, HBaseSpatialRDDProvider}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
  * Created by afox on 4/17/17.
  */
class BigtableSparkRDDProvider extends HBaseSpatialRDDProvider {
  override def canProcess(params: java.util.Map[String, java.io.Serializable]): Boolean =
    BigtableDataStoreFactory.canProcess(params)

  override def rdd(conf: Configuration,
          sc: SparkContext,
          dsParams: Map[String, String],
          origQuery: Query): SpatialRDD = {
    import org.locationtech.geomesa.index.conf.QueryHints._

    import scala.collection.JavaConversions._
    val ds = DataStoreFinder.getDataStore(dsParams).asInstanceOf[HBaseDataStore]

    // get the query plan to set up the iterators, ranges, etc
    lazy val sft = ds.getSchema(origQuery.getTypeName)
    lazy val qp = ds.getQueryPlan(origQuery).head

    if (ds == null || sft == null || qp.isInstanceOf[EmptyPlan]) {
      val transform = origQuery.getHints.getTransformSchema
      SpatialRDD(sc.emptyRDD[SimpleFeature], transform.getOrElse(sft))
    } else {
      val query = ds.queryPlanner.configureQuery(origQuery, sft)
      val transform = query.getHints.getTransformSchema
      GeoMesaConfigurator.setSchema(conf, sft)
      GeoMesaConfigurator.setSerialization(conf)
      GeoMesaConfigurator.setIndexIn(conf, qp.filter.index)
      GeoMesaConfigurator.setTable(conf, qp.table.getNameAsString)
      transform.foreach(GeoMesaConfigurator.setTransformSchema(conf, _))
      qp.filter.secondary.foreach { f => GeoMesaConfigurator.setFilter(conf, ECQL.toCQL(f)) }
      val scans = qp.ranges.map { s =>
        val scan = s
        // need to set the table name in each scan
        scan.setAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME, qp.table.getName)
        MultiTableInputFormatBase.scanToString(scan.asInstanceOf[BigtableExtendedScan])
      }
      conf.setStrings(MultiTableInputFormat.SCANS, scans: _*)

      val rdd = sc.newAPIHadoopRDD(conf, classOf[GeoMesaBigtableInputFormat], classOf[Text], classOf[SimpleFeature]).map(U => U._2)
      SpatialRDD(rdd, transform.getOrElse(sft))
    }
  }

}

class GeoMesaBigtableInputFormat extends InputFormat[Text, SimpleFeature] {
  var delegate: BigtableMultiTableInputFormat = _

  var sft: SimpleFeatureType = _
  var table: HBaseFeatureIndex = _

  private def init(conf: Configuration) = if (sft == null) {
    sft = GeoMesaConfigurator.getSchema(conf)
    table = HBaseFeatureIndex.index(GeoMesaConfigurator.getIndexIn(conf))
    delegate = new BigtableMultiTableInputFormat(TableName.valueOf(GeoMesaConfigurator.getTable(conf)))
    delegate.setConf(conf)
    // see TableMapReduceUtil.java
    HBaseConfiguration.merge(conf, HBaseConfiguration.create(conf))
    conf.set(TableInputFormat.INPUT_TABLE, GeoMesaConfigurator.getTable(conf))
  }

  /**
    * Gets splits for a job.
    */
  override def getSplits(context: JobContext): java.util.List[InputSplit] = {
    init(context.getConfiguration)
    val splits = delegate.getSplits(context)
    splits
  }

  override def createRecordReader(split: InputSplit,
                                  context: TaskAttemptContext): RecordReader[Text, SimpleFeature] = {
    init(context.getConfiguration)
    val rr = delegate.createRecordReader(split, context)
    val transformSchema = GeoMesaConfigurator.getTransformSchema(context.getConfiguration)
    val q = GeoMesaConfigurator.getFilter(context.getConfiguration).map { f => ECQL.toFilter(f) }
    new HBaseGeoMesaRecordReader(sft, table, rr, q, transformSchema)
  }

}




object MultiTableInputFormat {
  /** Job parameter that specifies the scan list. */
  val SCANS = "hbase.mapreduce.scans"
}

class BigtableMultiTableInputFormat(val name: TableName) extends MultiTableInputFormatBase with Configurable {
  setName(name)

  /** The configuration. */
  private var conf: Configuration = null


  /**
    * Returns the current configuration.
    *
    * @return The current configuration.
    * @see org.apache.hadoop.conf.Configurable#getConf()
    */
  def getConf: Configuration = conf

  /**
    * Sets the configuration. This is used to set the details for the tables to
    * be scanned.
    *
    * @param configuration The configuration to set.
    * @see   org.apache.hadoop.conf.Configurable#setConf(
    *        org.apache.hadoop.conf.Configuration)
    */
  def setConf(configuration: Configuration): Unit = {
    this.conf = configuration
    val rawScans = conf.getStrings(MultiTableInputFormat.SCANS)
    if (rawScans.length <= 0) throw new IllegalArgumentException("There must be at least 1 scan configuration set to : " + MultiTableInputFormat.SCANS)
    val s = new java.util.ArrayList[Scan]
    rawScans.foreach { r => s.add(MultiTableInputFormatBase.stringToScan(r)) }
    setScans(s)
  }
}

