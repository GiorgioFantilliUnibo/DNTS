package cli

import domain.authentication.NodeRole

/**
 * Container representing the raw state of parsed command-line arguments.
 *
 * @param role       The operating [[NodeRole]] of the node.
 * @param configFile The optional file path to the simulation configuration.
 * @param seedAddress   The target IP address (required for Client nodes).
 * @param port The target port number (required for Client nodes).
 */
case class CliOptions(
  role: Option[NodeRole] = None,
  cluster: Option[String] = None,
  configFile: Option[String] = None,
  seedAddress: Option[String] = None,
  port: Option[Int] = None
):

  /**
   * Performs semantic validation of the accumulated options.
   * It ensures that the specific combination of flags is valid for the selected role.
   *
   * @return `Right` containing the validated tuple (Role, ConfigPath, IP, Port) if successful,
   * or `Left` with an error message if the configuration is invalid.
   */
  def validate: Either[String, (NodeRole, Option[String], Option[String], Option[String], Option[Int])] =
    role match
      case None =>
        Left("Missing required parameter: --role <seed|client>")

      case Some(NodeRole.Seed) =>
        (cluster, configFile, port) match
          case (Some(name), Some(config), Some(port)) =>
            Right((NodeRole.Seed, Some(name), Some(config), None, Some(port)))
          case _ =>
            Left("Seed node requires --cluster <ClusterName>, --config <path> and --port <int> parameters.")

      case Some(NodeRole.Client) =>
        (cluster, seedAddress, port) match
          case (Some(clusterName), Some(address), Some(port)) =>
            Right((NodeRole.Client, Some(clusterName), configFile, Some(address), Some(port)))
          case _ =>
            Left("Client nodes requires --cluster <ClusterName>, --seedAddress <address> and --port <number>.")
