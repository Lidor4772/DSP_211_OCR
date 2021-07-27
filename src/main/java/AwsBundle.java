import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.amazonaws.util.EC2MetadataUtils;

import java.io.File;
import java.io.InputStream;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;



public class AwsBundle {
    private static final int MAX_INSTANCES = 19 ;
    private final AmazonEC2 ec2;
    private final AmazonS3 s3;
    private final AmazonSQS sqs;


    public final String requestsAppsQueueName = "requestsAppsQueue";
    public final String requestsWorkersQueueName = "requestsWorkersQueue";
    public final String resultsWorkersQueueName = "resultsWorkersQueue";

    public static final String bucketName = "ocr-assignment1";
    public static final String inputFolder = "input-files";
    public static final String outputFolder = "output-files";
    public static final String resultQueuePrefix = "resultQueue_";


    public static final String ami = "ami-0b001df5bda5a05bf";

    //message from local
    public final int messageType = 0;
    public final int uniqueLocalFilePath = 1;
    public final int outputFilepath = 2;
    public final int workersRatio = 3;
    static final String Delimiter = "X";

    //message from worker
    public final int urlIndex = 0;
    public final int textIndex = 1;

    //message to worker
    public final int localIdIndex = 0;
    public final int lineNumberIndex = 1;
    public final int urlWorkerIndex = 2;


    private static final AwsBundle instance = new AwsBundle();

    private AwsBundle(){
        ec2 = AmazonEC2ClientBuilder.defaultClient();
        s3 = AmazonS3ClientBuilder.defaultClient();
        sqs = AmazonSQSClientBuilder.defaultClient();
    }

    public static AwsBundle getInstance()
    {
        return instance;
    }


    public boolean checkIfInstanceExist(String name)
    {
        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
        DescribeInstancesResult describeInstancesResult = this.ec2.describeInstances(describeInstancesRequest);
        for (Reservation r : describeInstancesResult.getReservations())
        {
            for (Instance i : r.getInstances())
            {
                if (!i.getState().getName().equals("running"))
                    continue;
                for (Tag t : i.getTags())
                {
                    if (t.getKey().equals("Name")&&t.getValue().equals(name))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String getMangerStatus()
    {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = this.ec2.describeInstances(request);
        Tag tagName = new Tag("Name","Manager");
        for(Reservation reservation : response.getReservations()) {
            for(Instance instance : reservation.getInstances()) {
                if (!instance.getState().getName().equals("running"))
                    continue;
                List<Tag> tags = instance.getTags();
                if (tags.contains(tagName))
                {
                    for (Tag t: tags)
                    {
                        if (t.getKey().equals("Status")) {
                            return t.getValue();
                        }
                    }
                }
            }
        }
        return null;
    }

    public void changeManagerStatusToTerminate()
    {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = this.ec2.describeInstances(request);
        Tag tagName = new Tag("Name","Manager");
        for(Reservation reservation : response.getReservations()) {
            for(Instance instance : reservation.getInstances()) {
                List<Tag> tags = instance.getTags();
                if (tags.contains(tagName))
                {
                    for (Tag t: tags)
                    {
                        if (t.getKey().equals("Status")) {
                            t.setValue("Termination");
                            CreateTagsRequest tag_request = new CreateTagsRequest()
                                    .withResources(instance.getInstanceId())
                                    .withTags(t);
                            CreateTagsResult tag_response = this.ec2.createTags(tag_request);
                            break;
                        }

                    }
                }
            }
        }
    }

    public void createBucketIfNotExists(String bucketName)
    {
        try {
            AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
            if (!s3Client.doesBucketExistV2(bucketName)) {
                // Because the CreateBucketRequest object doesn't specify a region, the
                // bucket is created in the region specified in the client.
                s3Client.createBucket(new CreateBucketRequest(bucketName));
            }
        } catch (AmazonS3Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void uploadFileToS3(String bucketName,String keyName,String filePath)
    {
        try{
            this.s3.putObject(bucketName,keyName,new File(filePath));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public InputStream downloadFileFromS3(String bucketName, String keyName)
    {
        try{
            return this.s3.getObject(new GetObjectRequest(bucketName, keyName)).getObjectContent();
        }
        catch (AmazonS3Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    public void deleteFileFromS3(String bucketName, String key){
        this.s3.deleteObject(bucketName, key);
    }


    public void sendMessage(String queueUrl, String msg)
    {
        SendMessageRequest send_msg_request = new SendMessageRequest()
                .withQueueUrl(queueUrl)
                .withDelaySeconds(10)
                .withMessageBody(msg);
        this.sqs.sendMessage(send_msg_request);
    }

    public String createMsgQueue(String queueName)
    {
        CreateQueueRequest create_request = new CreateQueueRequest(queueName);
        try {
            this.sqs.createQueue(create_request);
        } catch (AmazonSQSException e) {
            if (!e.getErrorCode().equals("QueueAlreadyExists")) {
                throw e;
            }
        }
        return this.sqs.getQueueUrl(queueName).getQueueUrl();
    }

    public void deleteQueue(String queueName){
        try {
            this.sqs.deleteQueue(new DeleteQueueRequest(queueName));
        }
        catch (AmazonS3Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public String getQueueUrl(String queueUrl) {
        // busy wait until node is active and its queue is instantiated
        while (true){
            try{
                return this.sqs.getQueueUrl(queueUrl).getQueueUrl();
            }
            catch(QueueDoesNotExistException e){
                continue;
            }
        }
    }

    public void cleanQueue(String queueName)
    {
        PurgeQueueRequest request = new PurgeQueueRequest(queueName);
        this.sqs.purgeQueue(request);
    }

    public List<Message> fetchNewMessages(String queueUrl) {
        // Default of maximum  new messages to fetch - 1. Possible values 1-10
        // Fetching in "short polling" manner - fetch message from subset of pushers (fairness)
        // return fast, empty result might be returned,false empty result might be returned
        ReceiveMessageRequest receiveRequest = new ReceiveMessageRequest()
                .withQueueUrl(queueUrl)
                .withWaitTimeSeconds(20);

        return this.sqs.receiveMessage(receiveRequest).getMessages();
    }

    public void deleteMessageFromQueue(String queueUrl, Message message)
    {
        this.sqs.deleteMessage(new DeleteMessageRequest(queueUrl, message.getReceiptHandle()));
    }

    public void createInstance(String name, String imageId,String userDataScript)
    {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult ec2Response = this.ec2.describeInstances(request);
        int numInstances = ec2Response.getReservations().size();
        if (numInstances>=MAX_INSTANCES)
            return;

        RunInstancesRequest run_request = new RunInstancesRequest()
                .withImageId(imageId)
                .withKeyName("DSP_211_OCR")
                .withIamInstanceProfile(new IamInstanceProfileSpecification().withName("admin"))
                .withInstanceType(InstanceType.T2Micro)
                .withSecurityGroupIds("sg-0a216aa1700a994ef")
                .withUserData(Base64.getEncoder().encodeToString(userDataScript.getBytes(UTF_8)))
                .withMaxCount(1)
                .withMinCount(1);

        RunInstancesResult run_response = this.ec2.runInstances(run_request);
        String reservation_id = run_response.getReservation().getInstances().get(0).getInstanceId();
        Tag tagName = new Tag()
                .withKey("Name")
                .withValue(name);
        Tag tagStatus = new Tag()
                .withKey("Status")
                .withValue("Active");
        CreateTagsRequest tag_request = new CreateTagsRequest()
                .withResources(reservation_id)
                .withTags(tagName,tagStatus);
        CreateTagsResult tag_response = this.ec2.createTags(tag_request);
        System.out.printf(
                "Successfully started EC2 instance %s based on AMI %s",
                reservation_id,"ami-0b001df5bda5a05bf");
    }

    public void terminateCurrentInstance()
    {
        String instanceId = EC2MetadataUtils.getInstanceId();
        List<String> instanceIds = new ArrayList<>();
        instanceIds.add(instanceId);
        TerminateInstancesRequest request = new TerminateInstancesRequest(instanceIds);
        this.ec2.terminateInstances(request);
    }

    public void changeDefaultVisibilityTimeout(String queueUrl, String timeout)
    {
        Map<String,String> attributes = new HashMap<>();
        attributes.put("VisibilityTimeout",timeout);
        SetQueueAttributesRequest request = new SetQueueAttributesRequest(queueUrl,attributes);
        try {
            this.sqs.setQueueAttributes(request);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }
    public final AmazonSQS getSqs()
    {
        return this.sqs;
    }

    public final AmazonEC2 getEc2() {
        return this.ec2;
    }
}
