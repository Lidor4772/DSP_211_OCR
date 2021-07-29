By:
Amit Maoz 206197717
Lidor Mordechay 312467509

Application General Description :

The application will get as an input a text file containing a list of URLs of images. Then, instances will be launched in AWS (workers). 
Each worker will download image files, use some OCR library to identify text in those images (if any) and display the image with the text in a webpage.
The use-case is as follows:
	1.User starts the application and supplies as input a file with URLs of image files and an integer n stating how many image files per worker.
	2.User gets back an html file with images and their text.


Preliminary Work:
	- Create credentials and save it on the local machine in the default directory (at linux - "~/.aws/credentials")
 	- Create Key Pair in your Amazon acount with the name "DSP_211_OCR" 
	- Create Role with Admin policy in your Amazon acount with the name "admin"
	- Create Security Group in your Amazon acount with the id "sg-0a216aa1700a994ef"
	- Create AMI image in your Amazon acount that contains java 8 and aws api installed, and save it with the name "ami-0b001df5bda5a05bf".
  	Instances should be of type T2_Micro (Altough if possible, creating the manager with instance of type T2_Large would improve scalability) 
	- Create a bucket in s3 with the name "ocr-assignment1"
	- Store the jar files Worker.jar, Manager.jar in the bucket under directory "jarFiles/"

The whole program took 04:05 minutes, with n=12.

 
Local Application:

	- Generates uniqueId
	- Checks if the manager instance is active, if not - bootstraps it from ami and user data shell script, which downloads "Manager.jar" from the path "ocr-assignment1/jarFiles" in s3 and runs it.
	- In case the manager was already active, checks it's status. If status is "Termination", prints a message which informs that the manager cannot handle the request, and exits.
	- Creates bucket with the name "ocr-assignment1", if it doesn't exist.
	- Uploads the input file to the bucket under the path "inputFolder/$uniqueId".
	- Send the file's location in s3 to the manager
	- Creates private Queue dedicated for results from manager with the name "resultQueue_$uniqueId"
	- Tries to fetch a result from the manager in a loop - a message which contains the location of the output file in s3.
	- Once fetched result, downloads the summary file from s3 and creates an html output file
	- Deletes the input file and summary file from s3, and its private queue

Manager: 

- Resources: 
    	The manager maintains 3 main resources:
     	- A ConcurrentHashMap<String,Pair<Integer,Integer>> which its key is localId, and its value is a 
        	Pair of number of urls to process for current local, and number of requested workers by this local.
     	- A ConcurrentHashMap<String, Pair<String,String>[]> which its key is localId and its value is an array
        	of Pairs of url and coresponding ocr text.
     	- A Maximum Heap implemented as PriorityQueue<Integer>, which maintains all number of requested workers by
       	the different local apps, and assists in the process of workers termination.

- The manager performs a couple of defined tasks, in a concurrent manner using a thread pool. 
  The tasks are:
    Task1 - Listening to new requests from local Apps
            - Runs in a loop and tries to fetch a non empty bunch of new messages from the local apps
            - Once fetches such list of new message, do for every message:
                - Add handling request task to the thread pool (task 2)
                - If the message is of type "termination", switch the manager status to "termination", so 
                  that no more local apps can send requests to the manager. 
                  when switch to "termination" status manager will process only requests have been sent by the
                  moment, and wait until all results are aggregated from workers. Then, the manager shuts itself down.
                - Delete the message from the queue
    Task2 - Handling a request from local Apps
            - Downloads the file from s3
            - Processes a list of urls from the file 
            - Calculates the number of workers to be created
            - Creates workers
            - Sends new image tasks to a queue shared by all workers

    Task3 - Listening to results from workers and handling them 
            - Runs in a loop and tries to fetch a non empty bunch of new messages from workers
            - Once fetches such list of new message, do for every message:
                - Saves the result for coresponding request made by specific local app 
                - If there are no more requests to handle for this local app:
                    - Reduces some workers if needed
                    - Creates summary file and uploads it to s3
                    - Delete the summary file from the machine
                    - Send the location in s3 to the local app's private queue
                    - Updates relevant data structures  
                    - If all jobs from all local apps are handled, and manager's status is "Termination",
                      then apply termination code : delete queues and shut down the manager's instance.
            - Deletes the message from the queue

Worker:

	- Runs in a loop and tries to fetch a non empty bunch of new messages from the manager 
	- Once fetches such list of new message, do for every message:
    		- Decides the type of the message - either "termination" message or "new image task"
        	-If it is a "new image task" message:
            		- Extracts the url from the message and applies OCR on it.
            		- Sends a result message back to the manager - the OCR text in case of success
              		or an error description in the case of an error. 

        	-If it is a "termination" message:
            		-If it the first message of this type - worker shuts itself down after finishing
             		the process of all new image tasks it has already fetched.
            		-If it is not the first "termination" message - it means that an earlier message
             		in the current list of fetched messages was a "termination" message, so that the
            		worker already knows it must shut itself down. In such case the worker delegates
            		the "termination" message back to the queue from which it has been fetched, so 
            		that another worker will fetch it.
    	- In any case, deletes from queue the message from queue so that it won't be handled twice.


Queues:
	- One-directional queue dedicated for requests from all local apps to be fetched by the manager (new tasks)
	- One queue dedicated for work (new image tasks) delegated by the manager to all workers. This queue is also used for sendind termination message by the manager to workers.
	- One queue dedicated for sending task results (done OCR tasks) from all workers to the manager.   
	- A private queue for each local app to fetch job results (done task) 
    
Security: 

	The credentials are saved in the default location on the local machine and passed to amazon 
	services using AmazonEC2Client, AmazonSQSClient and AmazonS3Client.
	We do not write them explicitly in any exposed piece of code, and they are sent on web in the secured manner implemented by Amazon.
	The ec2 instances (manager and workers) are instantiated with an IAM role. 
	The IAM role is defined by a policy which allows full accessing of EC2,S3, and SQS services.     
	In addition, the scripts that are sent in the program are encrypted.

Scalability:

	We support scalability by performing all manager's tasks concurrently using a thread pool.
	The thread pool recycles threads to process the different tasks. It manages the number of threads
	according to amount of tasks dynamically and efficiently.
	In addition, all workers share the same queue - a property which allows idle workers to fetch and process 
	new image tasks when there are such. 
	We also manage dynamically the number of current workers using Maximum Heap and make sure it correspondes 
	to the maximum of all numbers of requested workers by current handled local apps.
	In addition, any item that can be deleted is immediately removed from the manager to avoid size overload.
	Moreover,  input files with maximum size bound of 10MB - may be changed

Persistence:

	Each message has a default visibility timeout. 
	We delete a message from the workers requests queue only after the result has been sent to the manager. 
	If a worker dies before processing the task, the task will be visible again after 
	the timeout passes so another worker can handle it.
	In case of the worker processing time of specific message is longer than its default visibility timeout 
	we perform a routine of "heart beat": when half of visibility timeout has passed and message is still not fully processed -
	increase the timeout by the amount of default visibilty timeout. 

 
Threads:

	The relevant place to use threads is in the Manager's code.
	We want the manager to be able to handle requests from many different local apps concurrently.
	For that mean we using a thread pool as an Executor, which manages dynamically the number of threads according
	to the amount of job and recycles threads to perform different tasks. 
	Threads tasks are detailed in the Manager paragraph. 


Limitations:

	One limitation we have encountered is the numbers of instances possible in the AWS account. We overcome this limitaion by verifying that the number of instances does not get higher then 19.
	We also limit the local apps to upload input files with maximum size bound (10MB - may be changed), to verify that one local app does not burden the whole system.

Work of workers:

	The manager does not delegate work to specific workers, but sends a task
	to a shared queue from which all workers can fetch the task in a race manner. Thanks to this design, in average no worker works much harder than other workers.


Parts in the program waiting for other parts:

	The only parts in the program that wait for other parts are those that necessarily have to wait - the manager waits for requests and results, the workers wait for new tasks, the local application waits for results. 

Termination:

	All queues and files are deleted during termination process. We also support the functionality that once the manager got termination message, local application will not send a new request. We implement this functionality using AWS tags - the manager has status tag which is switched from "Active" to "Termination" when termination message is sent. 


