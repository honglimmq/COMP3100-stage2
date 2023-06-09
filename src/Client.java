/**
 * A client side simulator that acts as a scheduler for a server-side simulator called ds-server.
 * 
 * <p>
 * The 'Client' class is responsible for scheduling job requests to be sent to the distributed
 * server simulator. It simulates the behaviour of a client by generating request data and sending
 * it to the server using 'serverCommunication.send' method. 
 * 
 * <p>
 * Created by Hong Lim (Student ID: 44679440) on 05 May, 2023.
 * </p>
 */

import java.util.List;
import java.util.ArrayList;
import util.*;
import util.Server;
import util.Job;
import util.enums.*;

public class Client {
  private int currentDSServerTime = -1;

  private ClientServerConnection serverCommunication;
  private Algorithm currAlgorithm;
  private Job currJob;
  private List<ServerXML> serverXML = null;


  public Client() {
    serverCommunication = new ClientServerConnection();
    currAlgorithm = Algorithm.CF;
    currJob = null;
  }

  public Client(Algorithm algo) {
    serverCommunication = new ClientServerConnection();
    currAlgorithm = algo;
    currJob = null;
  }

  public void run() {
    // Estabalish connection with ds-server
    serverCommunication.connect();

    // TCP handshake: Greeting + Authentication
    serverCommunication.send(Command.HELO);
    serverCommunication.recieve();
    serverCommunication.send(Command.AUTH, System.getProperty("user.name"));
    serverCommunication.recieve();


    // Read ds-system.xml
    serverXML = ServerXML.parse("ds-system.xml");


    while (!(serverCommunication.getReceivedMessage().equals(ServerCommand.NONE.toString()))) {
      // Signal ds-server for a job
      serverCommunication.send(Command.REDY);
      serverCommunication.recieve();

      String[] splittedMsg = serverCommunication.getReceivedMessage().split("\\s+");
      ServerCommand receivedCommand = ServerCommand.valueOf(splittedMsg[0]);

      switch (receivedCommand) {
        case JOBP:

        case JOBN:
          currentDSServerTime = Integer.parseInt(splittedMsg[1]);
          handleJob(splittedMsg);
          break;
        case JCPL:
          // job completion details, i.e. JCPL endTime jobID serverType serverID
          currentDSServerTime = Integer.parseInt(splittedMsg[1]);
        case NONE:
        default:
          break;
      }
    }
    // Exit gracefully
    System.exit(serverCommunication.close());
  }

  private void handleJob(String jobInfo[]) {
    Server chosenServer = null;
    currJob = Job.parseJobInfo(jobInfo);

    switch (currAlgorithm) {
      case FC:
        chosenServer = firstCapableAlgorithm(currJob.reqCore, currJob.reqMemory, currJob.reqDisk);
        break;
      case FF:
      case BF:
      case WF:
      case CF:
        chosenServer = closestFitAlgorithm(currJob.reqCore, currJob.reqMemory, currJob.reqDisk);
        break;
      case FT:
        chosenServer =
            fastestTurnaroundAlgorithm(currJob.reqCore, currJob.reqMemory, currJob.reqDisk);
        break;
      default:
        chosenServer =
            fastestTurnaroundAlgorithm(currJob.reqCore, currJob.reqMemory, currJob.reqDisk);
        break;
    }

    // SCHD
    if (chosenServer != null) {
      serverCommunication.send(Command.SCHD,
          currJob.jobID + " " + chosenServer.serverType + " " + chosenServer.serverID);
      serverCommunication.recieve();
    }
  }

  // ##################################
  // ## Custom Scheduling Algorithms ##
  // ##################################

  Server fastestTurnaroundAlgorithm(int reqCore, int reqMem, int reqDisk) {
    List<Server> servers = getServerInfo(GETSMode.Avail, reqCore, reqMem, reqDisk);
    if (servers == null || servers.isEmpty()) {
      servers = getServerInfo(GETSMode.Capable, reqCore, reqMem, reqDisk);
    }

    int chosenServerIndex = -1;
    int minEstimatedWaitingTime = Integer.MAX_VALUE;
    for (int i = 0; i < servers.size(); i++) {
      // query the total estimated waiting time i.e. EJWT serverType serverID
      Server server = servers.get(i);
      serverCommunication.send(Command.EJWT, server.serverType + " " + server.serverID);
      int estWaitingTime = Integer.parseInt(serverCommunication.recieve());

      if (minEstimatedWaitingTime > estWaitingTime) {
        minEstimatedWaitingTime = estWaitingTime;
        chosenServerIndex = i;
      }
    }

    return servers.get(chosenServerIndex);
  }

  Server closestFitAlgorithm(int reqCore, int reqMem, int reqDisk) {
    // Query and return available server with required resource based on GETS Avail
    List<Server> servers = getServerInfo(GETSMode.Avail, reqCore, reqMem, reqDisk);

    // If no server data is retrieved from GETS Avail, try getting data from GETS Capable instead.
    if (servers == null || servers.isEmpty()) {
      servers = getServerInfo(GETSMode.Capable, reqCore, reqMem, reqDisk);
      if (servers == null || servers.isEmpty()) {
        // No available servers

        return null;
      }
    }

    int chosenServerIndex = -1;
    int backupServerIndex = -1;
    int smallestFitnessValueCore = Integer.MAX_VALUE;
    int smallestFitnessValueMemory = Integer.MAX_VALUE;

    for (int i = 0; i < servers.size(); i++) {
      int fitnessValueCore = servers.get(i).core - reqCore;
      int fitnessValueMemory = servers.get(i).memory - reqMem;

      // Selection process:
      // 1.Select a server with the smallest positive core fitness value. 
      // 2.If given 2 servers of the samefitness value, use memory fitness value as a tiebreaker. 
      // 3.If however, there is no positive fitness value server, pick the closest negative fitness value server to 0.
      if (fitnessValueCore < smallestFitnessValueCore && fitnessValueCore >= 0) {
        smallestFitnessValueCore = fitnessValueCore;
        smallestFitnessValueMemory = fitnessValueMemory;
        chosenServerIndex = i;
      } else if (fitnessValueCore == smallestFitnessValueCore) {
        if (fitnessValueMemory < smallestFitnessValueMemory && fitnessValueMemory >= 0) {
          smallestFitnessValueMemory = fitnessValueMemory;
          chosenServerIndex = i;
        }
      } else if (chosenServerIndex == -1 && fitnessValueCore < smallestFitnessValueCore) {
        smallestFitnessValueCore = fitnessValueCore;
        smallestFitnessValueMemory = fitnessValueMemory;
        backupServerIndex = i;
      }
    }

    if (chosenServerIndex == -1) {
      chosenServerIndex = backupServerIndex;
    }

    return servers.get(chosenServerIndex);
  }

  // ####################################
  // ## Baseline Scheduling Algorithms ##
  // ####################################

  Server firstCapableAlgorithm(int reqCore, int reqMem, int reqDisk) {
    List<Server> servers = getServerInfo(GETSMode.Capable, reqCore, reqMem, reqDisk);

    // Return first server from GETS Capable
    return servers.get(0);
  }

  // #####################
  // ## Ulility Methods ##
  // #####################

  private List<Server> getServerInfo(GETSMode GetsMode, int reqCore, int reqMemory, int reqDisk) {
    // Generate outgoing message for GETS command with appropriate GETSMode
    serverCommunication.send(Command.GETS,
        GetsMode.toString() + " " + reqCore + " " + reqMemory + " " + reqDisk);

    // Should recieve DATA [nRecs] [recLen]
    serverCommunication.recieve();
    String[] spiltedMsg = serverCommunication.getReceivedMessage().split("\\s++");
    int numOfServer = Integer.parseInt(spiltedMsg[1]);
    List<Server> servers = new ArrayList<>();

    if (numOfServer != 0) {
      serverCommunication.send(Command.OK);
      // Process servers information
      for (int i = 0; i < numOfServer; i++) {
        serverCommunication.recieve();
        spiltedMsg = serverCommunication.getReceivedMessage().split("\\s++");
        servers.add(Server.parseServerInfo(spiltedMsg));
      }
    }

    serverCommunication.send(Command.OK);
    serverCommunication.recieve(); // RECV .

    return servers;
  }

  public static void main(String args[]) {
    // Check if any command-line arguments are passed
    if (args.length == 0) {
      new Client().run();
    } else if (args.length == 2 && args[0].equals("-a")) {
      String arg = args[1];
      Algorithm algo = null;

      // Pick an algorithm
      switch (arg) {
        case "fc":
          algo = Algorithm.FC;
          break;
        case "bf":
          algo = Algorithm.BF;
          break;
        case "ff":
          algo = Algorithm.FF;
          break;
        case "wf":
          algo = Algorithm.WF;
          break;
        case "atl":
          algo = Algorithm.ATL;
          break;
        case "cf":
          algo = Algorithm.CF;
          break;
        case "ft":
          algo = Algorithm.FT;
          break;
        default:
          algo = Algorithm.CF;
          break;
      }
      new Client(algo).run();
    }
  }
}
