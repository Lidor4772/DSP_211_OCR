
import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//import javafx.util.Pair;


import java.io.*;
import java.lang.reflect.Type;
import java.util.*;


import static java.nio.charset.StandardCharsets.UTF_8;

public class Local {



    final static AwsBundle awsBundle = AwsBundle.getInstance();

    public static void main(String[] args) {


        final String uniqueLocalId = UUID.randomUUID().toString().toLowerCase();
        final String uniquePathLocalApp =  awsBundle.inputFolder+"/"+ uniqueLocalId;
        boolean shouldTerminate = false;
        boolean gotResult = false;

        if(args.length == 3 || args.length == 4) {
            if (args.length == 4) {
                if (args[3].equals("terminate"))
                    shouldTerminate = true;
                else {
                    System.err.println("Invalid command line argument: " + args[4]);
                    System.exit(1);
                }
            }
        }
        else {
            System.err.println("Invalid number of command line arguments");
            System.exit(1);
        }

        if (!isLegalFileSize(new File(args[0])))
        {
            System.out.println("Input file is over maximal size (10MB)");
            System.exit(1);
        }

        if(!awsBundle.checkIfInstanceExist("Manager"))
        {
                createManager();
        }

        while (!awsBundle.checkIfInstanceExist("Manager"))
        {
            System.out.println("Instantiating manager..");
        }

        if(awsBundle.getMangerStatus().equals("Termination"))
        {
            System.out.println("The manager is in termination process and can not handle request..");
            System.exit(1);
        }

        String resultQueueUrl = awsBundle.createMsgQueue(AwsBundle.resultQueuePrefix+uniqueLocalId);
        awsBundle.createBucketIfNotExists(AwsBundle.bucketName);
        awsBundle.uploadFileToS3(AwsBundle.bucketName,uniquePathLocalApp,args[0]);

        String managerQueueUrl= awsBundle.getQueueUrl(awsBundle.requestsAppsQueueName); //blocking method, waits until manager is active & managerQueue is initialized
        String type = shouldTerminate ? "terminate" : "input";
        awsBundle.sendMessage(managerQueueUrl,createMessage(type,uniquePathLocalApp,AwsBundle.Delimiter,args[1].toLowerCase(),args[2]));
        System.out.println("\nMessage sent");

        while(!gotResult)
        {

            List<Message> messages= awsBundle.fetchNewMessages(resultQueueUrl);
            if (!messages.isEmpty())
            {
                String result = messages.get(0).getBody();
                if (result.equals("terminate message"))
                {
                    terminate(1,uniquePathLocalApp,resultQueueUrl,result);
                    System.exit(0);
                }
                InputStream resultFile = awsBundle.downloadFileFromS3(AwsBundle.bucketName,result);
                Gson gson = new Gson();
                try (Reader reader = new InputStreamReader(resultFile)) {
                    // Convert JSON File to Java Object
                    Pair<String,String>[] urlsAndText = gson.fromJson(reader, Pair[].class);
                    createHtmlFile(urlsAndText,args[1]);
                    System.out.println("Html file created successfully\n");
                    terminate(0,uniquePathLocalApp,resultQueueUrl,result);
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }

    private static void terminate(int status,String uniquePathLocalApp,String resultQueueUrl,String result) {
        // Termination
        awsBundle.deleteFileFromS3(AwsBundle.bucketName,uniquePathLocalApp);
        awsBundle.deleteQueue(resultQueueUrl);
        if (status==1)
            System.out.println("manager is in termination status and cannot handle request");
        if (status==0)
            awsBundle.deleteFileFromS3(AwsBundle.bucketName,result);
    }


    private static void createManager()
    {
        String managerScript = "#! /bin/bash\n" +
                "sudo yum update -y\n" +
                "mkdir ManagerFiles\n" +
                "aws s3 cp s3://ocr-assignment1/JarFiles/Manager.jar ./ManagerFiles\n" +
                "java -jar /ManagerFiles/Manager.jar\n";

        awsBundle.createInstance("Manager",AwsBundle.ami,managerScript);
    }

    private static String createMessage(String type, String filePath,String delimiter,String outputFileName,String ratio)
    {
        StringBuilder message = new StringBuilder();
        message.append(type);
        message.append(delimiter);
        message.append(filePath);
        message.append(delimiter);
        message.append(outputFileName);
        message.append(delimiter);
        message.append(ratio);
        return message.toString();
    }

    private static void createHtmlFile(Pair<String,String>[] urlsAndText,String outputFilePath)
    {
        try {
            PrintWriter writer = new PrintWriter(outputFilePath + ".html");
            writer.println("<html>\n\t<body>");
            for (Pair<String,String> urlAndText : urlsAndText) {
                String url = urlAndText.getFirst();
                String text = urlAndText.getSecond();
                writer.println("\t\t<img src=\""+ url+ "\" width=\"150\" height=\"150\">");
                writer.println("\t\t<br>");
                writer.println("\t\t"+text);
                writer.println("\t\t<br>\n\t\t<br>");
            }
            writer.println("\t</body>\n</html>");
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static boolean isLegalFileSize(File file){
        boolean isLegal = true;
        int maximumFileSize = 10000000; //10 MB
        if (file.length()>maximumFileSize)
            isLegal = false;
        return isLegal;
    }


}

