import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.asprise.ocr.Ocr;
import java.net.URL;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Worker {
    final static AwsBundle awsBundle = AwsBundle.getInstance();
    static boolean isProcessed;
    public static void main(String[] args) {
        int urlCount =0;
        boolean shouldTerminate = false;
        Timer timer = new Timer();
        while (!shouldTerminate)
        {

            try{
                System.out.println("Trying to fetch...");
                List<Message> urls = awsBundle.fetchNewMessages(awsBundle.requestsWorkersQueueName);
                for (Message message : urls)
                {
                    isProcessed = false;
                    // Note : first, we thought to call deleteMassage here, to handle the case
                    // when the message is very heavy and before it is fully processed it becomes visible
                    // again in the queue and another worker might start processing it.
                    // yet, we decided to delete the message from queue only after it is fully processed
                    // to handle the case that the worker dies unexpectedly.
                    // another possible solution might be implemented in the manager - waiting for response for some timeout
                    // and if not receiving such response , request again
                    if (message.getBody().equals("termination"))
                    {

                        if (!shouldTerminate) { //got first termination message ;
                            shouldTerminate = true;
                            awsBundle.deleteMessageFromQueue(awsBundle.requestsWorkersQueueName, message);
                            continue;
                        }
                        else
                        {
                            //got another termination message, sending it back to the queue
                            awsBundle.deleteMessageFromQueue(awsBundle.requestsWorkersQueueName, message);
                            awsBundle.sendMessage(awsBundle.requestsWorkersQueueName, "termination");
                            continue;
                        }
                    }
                    TimerTask task = setTimerTask(message,120); // Heart bit
                    timer.schedule(task,60000,60000);
                    System.out.println("Url number:"+ urlCount++);
                    String[] urlElements = message.getBody().split("%%%");
                    String localId = urlElements[awsBundle.localIdIndex];
                    String lineNumber = urlElements[awsBundle.lineNumberIndex];
                    String url = urlElements[awsBundle.urlWorkerIndex];
                    System.out.println(url);
                    try {
                        String text = applyOcr(url);
                        if (text.equals("") || (text.charAt(0) == '<'))
                            text = "OCR Failed";
                        System.out.println(text);
                        awsBundle.sendMessage(awsBundle.resultsWorkersQueueName, createMessage(localId+"%%%"+lineNumber+"%%%"+url,"XXX",text));
                        awsBundle.deleteMessageFromQueue(awsBundle.requestsWorkersQueueName,message);
                        //delete the message only after handled, so that if the node dies unexpectedly another node will process it
                        //no risk of double delete of the same message - aws api does not throw exception in such case
                        isProcessed = true;

                    }
                    catch (Exception e) // In case of exception the worker inform it and continuing to the next url
                    {
                        System.out.println("Exception e:" + e.getMessage());
                        awsBundle.sendMessage(awsBundle.resultsWorkersQueueName,createMessage(localId+"%%%"+lineNumber+"%%%"+url,"XXX",e.getMessage()));
                        awsBundle.deleteMessageFromQueue(awsBundle.requestsWorkersQueueName,message);
                        isProcessed = true;
                    }

                }
            }
            catch (QueueDoesNotExistException e)
            {
                System.exit(1);
            }
        }
        awsBundle.terminateCurrentInstance();
    }

    private static TimerTask setTimerTask(Message message,int visibilityTimeout) {
        // part of implementation of heartbeat - to handle large tasks
        TimerTask task = new TimerTask(){
            public void run() {
                if (!isProcessed)
                {
                    ChangeMessageVisibilityRequest request = new ChangeMessageVisibilityRequest()
                            .withQueueUrl(awsBundle.requestsWorkersQueueName)
                            .withReceiptHandle(message.getReceiptHandle())
                            .withVisibilityTimeout(visibilityTimeout);
                    System.out.println("message visibility changed");
                    awsBundle.getSqs().changeMessageVisibility(request);

                }
                else{
                    cancel();
                }
            }
        };
        return task;
    }

    private static String applyOcr(String url) throws Exception {
        Ocr.setUp(); // one time setup
        Ocr ocr = new Ocr(); // create a new OCR engine
        ocr.startEngine("eng", Ocr.SPEED_FASTEST); // English
        String text = ocr.recognize(new URL[]{new URL(url)},
                Ocr.RECOGNIZE_TYPE_ALL, Ocr.OUTPUT_FORMAT_PLAINTEXT); // PLAINTEXT | XML | PDF | RTF
        ocr.stopEngine();
        return text;
    }

    private static String createMessage(String url,String delimiter,String text)
    {
        return url + delimiter + text;
    }
}