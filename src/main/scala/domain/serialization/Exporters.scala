package domain.serialization

import akka.actor.Address
import domain.network.Model

object Exporters:

  trait Exporter[T]:
    extension (obj: T)
      def jsonExport: String


  /**
   * Exporter for generating detailed JSON representations of a [[Model]].
   */
  given modelExporter: Exporter[Model] with

    extension (model: Model)
      def jsonExport: String =
        val net = model.network
        val allLayers = net.layers

        def exportLayer(layer: domain.network.Layer, idx: Int): String =
          val neurons = layer.weights.rows
          val inputSize = layer.weights.cols
          val biasString = layer.biases.toList.mkString("[", ", ", "]")
          val weightsString = layer.weights.toFlatList
            .grouped(inputSize)
            .map(_.mkString("        [", ", ", "]"))
            .mkString("[\n", ",\n", "\n      ]")

          s"""{
            |      "layer_index": $idx,
            |      "activation": "${layer.activation.name}",
            |      "input_size": $inputSize,
            |      "neurons_count": $neurons,
            |      "parameters": {
            |        "biases": $biasString,
            |        "weights": $weightsString
            |      }
            |    }""".stripMargin

        val featuresJson = model.features.map(f => s"\"$f\"").mkString("[", ", ", "]")

        val hiddenLayersJson = allLayers.dropRight(1).zipWithIndex.map {
          case (layer, idx) => exportLayer(layer, idx)
        }.mkString(",\n")

        val outputLayerJson = allLayers.lastOption match {
          case Some(layer) => exportLayer(layer, allLayers.size - 1)
          case None => "{}"
        }

        s"""model {
          | features = $featuresJson
          | hidden-layers = $hiddenLayersJson
          | output-layer = $outputLayerJson
          |}""".stripMargin

  given addressSetExporter: Exporter[Set[Address]] with
    extension (addresses: Set[Address])
      def jsonExport: String =

        def exportAddress(address: Address): String =
          val host = address.host.map(h => s""""$h"""").getOrElse("null")
          val port = address.port.map(_.toString).getOrElse("null")

          s"""{
             |  "protocol": "${address.protocol}",
             |  "system": "${address.system}",
             |  "host": $host,
             |  "port": $port
             |}""".stripMargin

        addresses
          .map(exportAddress)
          .mkString("[\n", ",\n", "\n]")