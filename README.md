# Distributed Neural Training Simulation (DNTS)

DNTS is a software framework designed for the simulation and analysis of neural network training in a distributed P2P (Peer-to-Peer) environment.  

The system implements a decentralized learning environment: autonomous nodes learn locally from their own data shard and use Gossip Learning to exchange and merge models asynchronously, achieving global consensus without any central server.  

Developed entirely in Scala 3 and Akka Cluster, the project strictly applies the principles of Functional Programming and the Actor Model.

## Build

The project uses sbt (Scala Build Tool). To generate the multi-platform executable (Fat JAR) containing the application and all its dependencies, run the following from the project root:

`sbt assembly`

Note: for convenience in the subsequent commands, we will rename the generated file to `dnts.jar`

## Running the System
The application is cross-platform (Windows, Linux, macOS) and only requires the installation of Java (JRE/JDK 11 or higher). The entire system can be started via the generated JAR, without external dependencies.

### 1. Simulation Configuration
The neural network topology, the dataset to be generated, and the training hyperparameters must be defined in a .conf configuration file.
Ensure that the file is present in the same folder where you are launching the Seed node's JAR.

### 2. Starting the Seed Node
The first node to launch is the seed. This node is responsible for reading the configuration file, starting the cluster, and acting as an entry point for other peers.

Open a terminal and run:

```console
java -jar dnts.jar --role seed --cluster MyCluster --config simulation.conf --port 2500
```

### 3. Starting the Client Nodes
Once the Seed node is started, you can launch as many client nodes as you wish. Each node will join the cluster, receive its portion of data, and participate in the Gossip Learning cycle.
Once a simulation instance is started, it will no longer be possible to add nodes to the created cluster.
Clients must provide authentication options and link to the seed node's address.

Open a terminal and run::
```console
# Starting and registering the first client
java -jar dnts.jar --role client --cluster MyCluster --seedAddress 127.0.0.1:2500 --port 2501 --action register --username alice --password secret --fullName "Alice Smith"

# Starting and logging in a second existing client
java -jar dnts.jar --role client --cluster MyCluster --seedAddress 127.0.0.1:2500 --port 2502 --action login --username bob --password secret
```
(You can add additional clients simply by specifying a different --port for each.)

## CLI Arguments
The executable accepts the following parameters:

### Common Parameters

These parameters are valid or required regardless of the node's role.

| Parameter          | Description                                                 | Requirement   |
|--------------------|-------------------------------------------------------------|---------------|
| `--role <role>`    | Defines the node's role in the cluster: `seed` or `client`. | **Mandatory** |
| `--cluster <name>` | The identifier name of the cluster.                         | **Mandatory** |
| `--port <port>`    | Listening binding port for the node (e.g., `2500`).         | **Mandatory** |
| `--help`           | Shows the CLI help menu.                                    | Optional      |


---

### Seed Node (`--role seed`)

The Seed node is the entry point of the cluster and manages the initial setup of the simulation.

| Parameter         | Description                                                                                                 | Requirement   |
|-------------------|-------------------------------------------------------------------------------------------------------------|---------------|
| `--config <path>` | Path to the configuration file (e.g., `simulation.conf`) containing topology, dataset, and hyperparameters. | **Mandatory** |

---

### Client Node (`--role client`)

Client nodes join the existing cluster, receive their portion of data, and participate in distributed training (Gossip Learning). They require additional parameters for connection and authentication.

| Parameter                 | Description                                                                           | Requirement   |
|---------------------------|---------------------------------------------------------------------------------------|---------------|
| `--seedAddress <address>` | IP address and port of the Seed node to connect to (e.g., `127.0.0.1:2500`).          | **Mandatory** |
| `--port <port>`           | Local binding port for the client node.                                               | **Mandatory** |
| `--action <action>`       | Action to perform towards the Seed: `register` (new user) or `login` (existing user). | **Mandatory** |
| `--username <name>`       | Client's username for authentication.                                                 | **Mandatory** |
| `--password <pwd>`        | Client's password.                                                                    | **Mandatory** |
| `--fullName <name>`       | User's full name (mainly useful during the `register` phase).                         | Optional      |