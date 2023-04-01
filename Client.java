import java.net.*;
import java.io.*;
import java.util.*;

enum Command {
  HELO, AUTH, REDY, OK, GETS, SCHD, ENQJ, DEQJ, LSTQ, CNTJ, EJWT, LSTJ, MIGJ, KILJ, TERM, QUIT
}


enum ServerCommand {
  DATA, JOBN, JOBP, JCPL, RESF, RESR, CHKQ, NONE, ERR, OK, QUIT
}


public class Client {
  private final String EMPTYSTRING = "";
  private final String BREAKLINE = "\n";
  private final String WHITESPACE = " ";
  private final int SERVERPORT = 50000;

  boolean debug = false;
  int count = 0;
  boolean firstPass = true;

  Socket socket;
  DataOutputStream out;
  BufferedReader in;
  String incomingMsg = EMPTYSTRING;
  String outgoingMsg = EMPTYSTRING;

  // Current job information
  int jobID = 0;
  int reqCore = 0;
  int reqMemory = 0;
  int reqDisk = 0;

  // Selected server information
  List<Server> servers = new ArrayList<Server>();
  int currentServerIndex = 0;

  public void run() throws IOException {
    // Connect
    socket = new Socket("localhost", SERVERPORT);
    out = new DataOutputStream(socket.getOutputStream());
    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

    // TCP handshake
    sendMsg(Command.HELO);
    recvMsg();
    sendMsg(Command.AUTH, System.getProperty("user.name"));
    recvMsg();

    while (!incomingMsg.equals(ServerCommand.NONE.toString())) {
      // Signal server for a job
      sendMsg(Command.REDY);
      incomingMsg = recvMsg();

      String[] splittedMsg = incomingMsg.split("\\s+");
      ServerCommand recvCommand = ServerCommand.valueOf(splittedMsg[0]);

      if (recvCommand.equals(ServerCommand.JOBN)) {
        // handles JOBN case
      }

      switch (recvCommand) {
        case JOBP:
        case JOBN:
          handleJob(splittedMsg);
          break;
        case JCPL:
        case NONE:
        default:
          break;
      }
      count++;
    }
    sendMsg(Command.QUIT);
    recvMsg();

    out.close();
    socket.close();
  }

  void handleJob(String jobInfo[]) throws IOException {
    parseJobInfo(jobInfo);

    if (firstPass) {
      // Generate outgoing message for GETS All command
      sendMsg(Command.GETS, "All");
      incomingMsg = recvMsg();

      // Determine jobID to schedule a server for
      String[] spiltedMsg = incomingMsg.split("\\s++");

      int numOfServer = Integer.parseInt(spiltedMsg[1]);
      int maxCore = -1;
      sendMsg(Command.OK);

      // LRR strategy:
      // Identifying the largest server type based on core
      // and count how many of that server type are there.
      // State information on each server is formmated as:
      // [serverType] [serverID] [state] [currStartTime] [core] [memory] [disk]
      for (int i = 0; i < numOfServer; i++) {
        incomingMsg = recvMsg();
        spiltedMsg = incomingMsg.split("\\s++");
        int core = Integer.parseInt(spiltedMsg[4]);
        String serverType = spiltedMsg[0];

        if (maxCore < core) { 
          // case 1: A larger number of core server
          servers.clear();
          servers.add(parseServerInfo(spiltedMsg));
          maxCore = core;
        } else if (servers.get(0).getServerType().equals(serverType) && maxCore == core) {
          // case 2: A same server type with same largest number of core
          servers.add(parseServerInfo(spiltedMsg));
        } else { 
          // case 3: A different server type that may have lower or same number of core
          // do nothing
        }
      }

      sendMsg(Command.OK);
      incomingMsg = recvMsg(); // RECV .
      firstPass = false;
    }


    // Schedule a job based on LRR strategy
    String serverType = servers.get(currentServerIndex).getServerType();
    int serverID = servers.get(currentServerIndex).getServerID();

    outgoingMsg = jobID + WHITESPACE + serverType + WHITESPACE + serverID;
    sendMsg(Command.SCHD, outgoingMsg);
    incomingMsg = recvMsg();

    // Manage server choice based on LRR strategy
    ++currentServerIndex;
    if (currentServerIndex >= servers.size()) {
      currentServerIndex = 0;
    }
  }

  String recvMsg() throws IOException {
    String message = in.readLine();

    // print server message
    if (debug) {
      System.out.println("RCVD " + message);
    }
    return message;
  }

  void sendMsg(Command cmd) throws IOException {
    sendMsg(cmd, EMPTYSTRING);
  }

  void sendMsg(Command cmd, String parameters) throws IOException {
    String message;
    if (!parameters.isEmpty()) {
      message = cmd + WHITESPACE + parameters + BREAKLINE;
    } else {
      message = cmd + BREAKLINE;
    }

    out.write(message.getBytes());
    out.flush();

    // print client message
    if (debug) {
      System.out.print("SENT " + message);
    }
  }

  int parseJobInfo(String[] jobinfo) {
    try {
      jobID = Integer.parseInt(jobinfo[2]);
      reqCore = Integer.parseInt(jobinfo[4]);
      reqMemory = Integer.parseInt(jobinfo[5]);
      reqDisk = Integer.parseInt(jobinfo[6]);
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("ArrayIndexOutOfBoundsException ==> " + e.getMessage());
    } catch (NumberFormatException e) {
      // TODO: handle exception
    }

    return jobID;
  }

  Server parseServerInfo(String[] jobInfo) {
    Server server = null;
    try {
      String serverType = jobInfo[0];
      int serverID = Integer.parseInt(jobInfo[1]);
      String status = jobInfo[2];
      int currStartTime = Integer.parseInt(jobInfo[3]);
      int core = Integer.parseInt(jobInfo[4]);
      int memory = Integer.parseInt(jobInfo[5]);
      int disk = Integer.parseInt(jobInfo[6]);
      int waitingJobs = Integer.parseInt(jobInfo[7]);
      int runningJobs = Integer.parseInt(jobInfo[8]);

      server = new Server(serverType, serverID, status, currStartTime, core, memory, disk,
          waitingJobs, runningJobs);
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("ArrayIndexOutOfBoundsException ==> " + e.getMessage());
    }

    return server;
  }



  public static void main(String args[]) throws Exception {
    new Client().run();
  }
}
