package cli

import domain.authentication.AuthAction.Register
import domain.authentication.{AuthAction, NodeRole}

/**
 * Container for authentication-related command-line options.
 *
 * @param action   The authentication action to perform.
 * @param username The username for authentication.
 * @param password The password for authentication.
 * @param fullName The optional full name (required for registration).
 */
case class AuthOptions(
                        action: Option[AuthAction],
                        username: String,
                        password: String,
                        fullName: Option[String] = None
                      )

/**
 * Container representing the raw state of parsed command-line arguments.
 *
 * @param role        The operating [[NodeRole]] of the node.
 * @param cluster     The name of the cluster.
 * @param configFile  The optional file path to the simulation configuration.
 * @param seedAddress The target IP address (required for Client nodes).
 * @param port        The target port number.
 * @param action      Action (login, registration) to be performed.
 * @param username    Username of the client performing the authentication.
 * @param password    Password of the client performing the authentication.
 * @param fullName    Optional full name of the client performing the authentication.
 */
case class CliOptions(
                       role: Option[NodeRole] = None,
                       cluster: Option[String] = None,
                       configFile: Option[String] = None,
                       seedAddress: Option[String] = None,
                       port: Option[Int] = None,
                       action: Option[AuthAction] = None,
                       username: Option[String] = None,
                       password: Option[String] = None,
                       fullName: Option[String] = None
                     ):

  /**
   * Performs semantic validation of the accumulated options.
   * It ensures that the specific combination of flags is valid for the selected role.
   *
   * @return `Right` containing the validated tuple (Role, Cluster, ConfigPath, IP, Port, AuthOptions) if successful,
   * or `Left` with an error message if the configuration is invalid.
   */
  def validate: Either[String, (NodeRole, Option[String], Option[String], Option[String], Option[Int], Option[AuthOptions])] =
    role match
      case None =>
        Left("Missing required parameter: --role <seed|client>")

      case Some(NodeRole.Seed) =>
        (cluster, configFile, port) match
          case (Some(name), Some(config), Some(p)) =>
            Right((NodeRole.Seed, Some(name), Some(config), None, Some(p), None))
          case _ =>
            Left("Seed node requires --cluster <ClusterName>, --config <path> and --port <int> parameters.")

      case Some(NodeRole.Client) =>
        (cluster, seedAddress, port, action, username, password) match
          case (Some(clusterName), Some(address), Some(p), Some(act), Some(usr), Some(pwd)) if act == AuthAction.Login || act == AuthAction.Register =>
            if act == AuthAction.Register && fullName.isEmpty then
              Left("Client registration requires --fullname <string> parameter.")
            else
              val authOpts = AuthOptions(Some(act), usr, pwd, fullName)
              Right((NodeRole.Client, Some(clusterName), None, Some(address), Some(p), Some(authOpts)))
          case _ =>
            Left("Client node requires --cluster, --seedAddress, --port, --action <login|register>, --username, and --password.")