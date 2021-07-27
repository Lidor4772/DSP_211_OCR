
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.model.Message;
import com.google.gson.Gson;


import java.io.*;
import java.util.*;

import java.util.concurrent.*;


public class Manager {

    final static AwsBundle awsBundle = AwsBundle.getInstance();

    private static final Gson gson = new Gson();
    private static final String requestsAppsQueueUrl = awsBundle.createMsgQueue(awsBundle.requestsAppsQueueName);
    private static final String requestsWorkersQueueUrl = awsBundle.createMsgQueue(awsBundle.requestsWorkersQueueName);
    private static final String resultsWorkersQueueUrl = awsBundle.createMsgQueue(awsBundle.resultsWorkersQueueName);

    private static final ConcurrentHashMap<String,Pair<Integer,Integer>> localsAndPairOfNumUrlsAndNumWorkers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Pair<String,String>[]> localsAndRequestsResults = new ConcurrentHashMap<>();
    private static final  PriorityQueue<Integer> maxHeapNumWorkersAndLocals = new PriorityQueue<>(Collections.reverseOrder());
    private static final ExecutorService threadPool =  Executors.newCachedThreadPool();
    private static Boolean isListening;
    private static Boolean shouldTerminate = false;
    private static int numOfCreatedSummaryFiles=0;



    public static void main(String[] args) throws InterruptedException {

        System.out.println("Main Thread");
        cleanQueues();
        isListening = false;
        maxHeapNumWorkersAndLocals.add(0);
        awsBundle.changeDefaultVisibilityTimeout(requestsWorkersQueueUrl,"120");

        List<Message> messages;
        while(!shouldTerminate)
        {
            messages = awsBundle.fetchNewMessages(requestsAppsQueueUrl);

            for(Message message : messages) {
                synchronized (isListening) {
                    if (!isListening)
                        listen();
                }
                String[] messageElements = message.getBody().split(AwsBundle.Delimiter);
                String uniqueLocalFilePath = messageElements[awsBundle.uniqueLocalFilePath];
                String uniqueLocalId = uniqueLocalFilePath.substring((AwsBundle.inputFolder + "/").length());
                if (shouldTerminate)
                {
                    sendTerminationMessage(uniqueLocalId);
                    continue;
                }
                threadPool.execute(() -> {
                    InputStream inputStream = awsBundle.downloadFileFromS3(AwsBundle.bucketName, uniqueLocalFilePath);
                    List<String> imagesUrls = processFile(inputStream, uniqueLocalId);
                    int numOfRequestedWorkers = calcNumOfWorkers(Integer.parseInt(messageElements[awsBundle.workersRatio]),imagesUrls.size());
                    int maxNumOfWorkers;
                    synchronized (maxHeapNumWorkersAndLocals) {
                        maxNumOfWorkers = maxHeapNumWorkersAndLocals.peek();
                        maxHeapNumWorkersAndLocals.add(numOfRequestedWorkers);
                    }
                    if (maxNumOfWorkers < numOfRequestedWorkers) {
                        for (int i = 0; i < numOfRequestedWorkers - maxNumOfWorkers; i++) {
                            createWorker();
                            System.out.println("\nCreate worker");
                        }
                    }
                    localsAndPairOfNumUrlsAndNumWorkers.put(uniqueLocalId,new Pair(imagesUrls.size(),numOfRequestedWorkers));
                    sendImageUrls(imagesUrls,uniqueLocalId);
                });
                if (messageElements[awsBundle.messageType].equals("terminate")) {
                    awsBundle.changeManagerStatusToTerminate();
                    awsBundle.deleteMessageFromQueue(awsBundle.requestsAppsQueueName, message);
                    System.out.println("Got termination message");
                    synchronized (shouldTerminate) {
                        shouldTerminate=true;
                    }
                }
                awsBundle.deleteMessageFromQueue(awsBundle.requestsAppsQueueName, message);
            }
        }
        threadPool.shutdown();
        while (!threadPool.awaitTermination(24, TimeUnit.HOURS)) {
            System.out.println("waiting for termination...");
        }
    }

    private static void sendTerminationMessage(String uniqueLocalId) {
        awsBundle.sendMessage(AwsBundle.resultQueuePrefix+uniqueLocalId,"terminate message");
    }

    private static void sendImageUrls(List<String> imagesUrls,String uniqueLocalId) {
        int lineNumber = 0;
        while (!imagesUrls.isEmpty()) {
            String imageUrl = imagesUrls.remove(0);
            awsBundle.sendMessage(requestsWorkersQueueUrl, uniqueLocalId + "%%%" + lineNumber + "%%%" + imageUrl);
            lineNumber++;
        }
    }

    private static int calcNumOfWorkers(int workersRatio,int numOfUrls) {
        int round = ((numOfUrls % workersRatio) == 0) ? 0 : 1;
        int numOfWorkers = (numOfUrls / workersRatio) + round;
        System.out.println("imageUrl.size(): " + numOfUrls);
        System.out.println("workers ratio: " + workersRatio);
        System.out.println("round: " + round);
        System.out.println("numOfWorkers: " + numOfWorkers);
        return numOfWorkers;
    }

    private static void cleanQueues() {
        awsBundle.cleanQueue(requestsWorkersQueueUrl);
        awsBundle.cleanQueue(resultsWorkersQueueUrl);
    }


    private static List<String> processFile(InputStream inputStream, String uniqueLocalId) {
        List<String> lines = new LinkedList<>();

        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (!line.equals(""))
                lines.add(line);
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        localsAndRequestsResults.put(uniqueLocalId,new Pair[lines.size()]);
        return lines;
    }


    private static void createWorker()
    {
        String workerScript = "#! /bin/bash\n" +
                "sudo yum update -y\n" +
                "mkdir WorkerFiles\n" +
                "aws s3 cp s3://ocr-assignment1/JarFiles/Worker.jar ./WorkerFiles\n" +
                "java -jar /WorkerFiles/Worker.jar\n";
        awsBundle.createInstance("Worker",AwsBundle.ami,workerScript);
    }

    private static void listen()
    {
        isListening=true;
        threadPool.execute(() -> {
            int numOfUrls=0;
            while(isListening) {
                System.out.println("listening...");
                List<Message> results = awsBundle.fetchNewMessages(awsBundle.resultsWorkersQueueName);
                for (Message result : results) {
                    System.out.print("Fetched result\n");
                    awsBundle.deleteMessageFromQueue(resultsWorkersQueueUrl,result);
                    String[] resultElements = result.getBody().split("XXX");
                    String[] urlElements = resultElements[awsBundle.urlIndex].split("%%%");
                    String uniqueLocalId = urlElements[0];
                    int lineNumber = Integer.parseInt(urlElements[1]);
                    String imageUrl = urlElements[2];
                    if (resultElements.length==1)
                        continue;
                    String text = resultElements[awsBundle.textIndex];
                    System.out.println("Url: "+numOfUrls++);
                    System.out.println("Line Number "+lineNumber);
                    System.out.println("Image Url:" + imageUrl);
                    System.out.println("Text:" + text);
                    Pair[] requestsResults = localsAndRequestsResults.get(uniqueLocalId);
                    if (requestsResults[lineNumber]!=null)
                    {
                        //message is already handled by some worker
                        continue;
                    }
                    requestsResults[lineNumber] = new Pair(imageUrl, text);

                    Integer remainingUrlsCurrLocal = localsAndPairOfNumUrlsAndNumWorkers.get(uniqueLocalId).getFirst();
                    Integer numOfWorkersForLocal = localsAndPairOfNumUrlsAndNumWorkers.get(uniqueLocalId).getSecond();
                    remainingUrlsCurrLocal--;
                    if (remainingUrlsCurrLocal == 0) {
                        boolean shouldReduceWorkers = false;
                        int currentMaxNumOfWorkers;
                        int nextMaxNumOfWorkers=0;
                        synchronized (maxHeapNumWorkersAndLocals) {
                            currentMaxNumOfWorkers = maxHeapNumWorkersAndLocals.peek();
                            maxHeapNumWorkersAndLocals.remove(numOfWorkersForLocal);
                            if (currentMaxNumOfWorkers == numOfWorkersForLocal) {
                                nextMaxNumOfWorkers = maxHeapNumWorkersAndLocals.peek();
                                shouldReduceWorkers = true; //created to minimize synchronization scope
                            }
                        }
                        if (shouldReduceWorkers)
                            reduceWorkers(currentMaxNumOfWorkers-nextMaxNumOfWorkers);

                        //create summary file and send it back to local
                        try {
                            String summaryFilePath = "Summary" + uniqueLocalId + ".json";
                            String s3SummaryFilePath = AwsBundle.outputFolder + "/" + summaryFilePath;
                            FileWriter fout = new FileWriter(summaryFilePath);
                            fout.write(gson.toJson(localsAndRequestsResults.get(uniqueLocalId)));
                            fout.flush();
                            fout.close();
                            awsBundle.uploadFileToS3(AwsBundle.bucketName, s3SummaryFilePath, summaryFilePath);
                            awsBundle.sendMessage(AwsBundle.resultQueuePrefix + uniqueLocalId, s3SummaryFilePath);
                            //At this point we can delete local entries from hashMaps
                            localsAndPairOfNumUrlsAndNumWorkers.remove(uniqueLocalId);
                            numOfCreatedSummaryFiles++;
                            File file = new File(summaryFilePath);
                            file.delete();
                            if (shouldTerminate && (numOfCreatedSummaryFiles == localsAndRequestsResults.size())) {
                                localsAndRequestsResults.remove(uniqueLocalId);
                                terminate();
                                isListening = false;
                                break;
                            }
                            localsAndRequestsResults.remove(uniqueLocalId);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else {
                        localsAndPairOfNumUrlsAndNumWorkers.put(uniqueLocalId, new Pair<>(remainingUrlsCurrLocal,numOfWorkersForLocal));
                    }
                }
            }
        });
    }

    private static void reduceWorkers(int numOfWorkersToReduce)
    {
        System.out.println("In reduce Workers");
        for (int i=0;i<numOfWorkersToReduce;i++) {
            System.out.println("Reducing workers by 1");
            awsBundle.sendMessage(awsBundle.requestsWorkersQueueName, "termination");
        }
    }

    private static void terminate()
    {
        //send termination messages to remaining apps
        List<Message> remainingRequestsFromLocals = awsBundle.fetchNewMessages(awsBundle.requestsAppsQueueName);
        while (!remainingRequestsFromLocals.isEmpty())
        {
            for (Message message: remainingRequestsFromLocals)
            {
                String[] messageElements = message.getBody().split(AwsBundle.Delimiter);
                String uniqueLocalFilePath = messageElements[awsBundle.uniqueLocalFilePath];
                String uniqueLocalId = uniqueLocalFilePath.substring((AwsBundle.inputFolder + "/").length());
                sendTerminationMessage(uniqueLocalId);
            }
            remainingRequestsFromLocals = awsBundle.fetchNewMessages(awsBundle.requestsAppsQueueName);
        }


        while (awsBundle.checkIfInstanceExist("Worker"))
        {
            System.out.println("waiting for workers to finish");
        }
        synchronized (isListening)
        {
            isListening = false;
        }
//        cleanQueues();
        deleteQueues();
        System.out.println("TERMINATED");
        awsBundle.terminateCurrentInstance();
    }

    private static void deleteQueues() {
        awsBundle.deleteQueue(awsBundle.requestsAppsQueueName);
        awsBundle.deleteQueue(awsBundle.requestsWorkersQueueName);
        awsBundle.deleteQueue(awsBundle.resultsWorkersQueueName);
    }

}

