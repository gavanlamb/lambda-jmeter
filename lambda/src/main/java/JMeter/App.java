package JMeter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.PutLifecycleEventHookExecutionStatusRequest;
import software.amazon.awssdk.services.codedeploy.model.PutLifecycleEventHookExecutionStatusResponse;
import software.amazon.awssdk.transfer.s3.CompletedDirectoryUpload;
import software.amazon.awssdk.transfer.s3.DirectoryUpload;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.report.config.ConfigurationException;
import org.apache.jmeter.report.dashboard.GenerationException;
import org.apache.jmeter.report.dashboard.ReportGenerator;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.Summariser;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.SearchByClass;
import software.amazon.awssdk.transfer.s3.UploadDirectoryRequest;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class App implements RequestHandler<Map<String, String>, String> {

    private LambdaLogger lambdaLogger;
    private String lineSeparator = System.lineSeparator();

    @Override
    public String handleRequest(
            Map<String, String> request,
            Context context) {
        lambdaLogger = context.getLogger();
        lambdaLogger.log("Request:" + request.toString() + lineSeparator);

        String deploymentId = request.get("DeploymentId");
        lambdaLogger.log("DeploymentId:" + deploymentId + lineSeparator);

        String lifecycleEventHookExecutionId = request.get("LifecycleEventHookExecutionId");
        lambdaLogger.log("LifecycleEventHookExecutionId:" + lifecycleEventHookExecutionId + lineSeparator);

        try {
            String path = System.getenv("LAMBDA_TASK_ROOT") == null ? System.getProperty("user.dir") : System.getenv("LAMBDA_TASK_ROOT");
            lambdaLogger.log("Path:" + path + lineSeparator);

            StandardJMeterEngine jMeter = new StandardJMeterEngine();
            lambdaLogger.log("Created jMeter" + lineSeparator);

            String jMeterPropertiesPath = GetJMeterPropertiesPath(path);
            lambdaLogger.log("JMeterPropertiesPath:" + jMeterPropertiesPath + lineSeparator);
            JMeterUtils.loadJMeterProperties(jMeterPropertiesPath);

            String jMeterPath = GetJMeterPath(path);
            lambdaLogger.log("JMeterPath:" + jMeterPath + lineSeparator);
            JMeterUtils.setJMeterHome(jMeterPath);
            lambdaLogger.log("JMeter home set" + lineSeparator);

            JMeterUtils.initLogging();
            lambdaLogger.log("Logging initialised" + lineSeparator);
            JMeterUtils.initLocale();
            lambdaLogger.log("Locale initialised" + lineSeparator);
            SaveService.loadProperties();
            lambdaLogger.log("Loaded properties" + lineSeparator);

            // Load JMX
            String testFileLocation = System.getenv("JMETER_TEST_FILE");
            lambdaLogger.log("TestFileLocation:" + testFileLocation + lineSeparator);
            File loadTestFile = new File(testFileLocation);
            lambdaLogger.log("Created loadTestFile" + lineSeparator);
            HashTree testPlanTree = SaveService.loadTree(loadTestFile);
            lambdaLogger.log("Loaded testPlanTree" + lineSeparator);
            String logFile = "/tmp/result.jtl";
            AddReporting(testPlanTree, logFile);
            AddArguments(testPlanTree);

            jMeter.configure(testPlanTree);
            lambdaLogger.log("Configured jMeter" + lineSeparator);
            jMeter.run();
            lambdaLogger.log("Executed jMeter" + lineSeparator);

            GenerateHtmlReport(logFile);
            UploadToS3();

            SetCodeDeployStatus(
                    "Succeeded",
                    lifecycleEventHookExecutionId,
                    deploymentId);
            return "Succeeded";
        } catch (Exception exception) {
            lambdaLogger.log("Exception occurred" + lineSeparator);
            lambdaLogger.log(exception + lineSeparator);

            SetCodeDeployStatus(
                    "Failed",
                    lifecycleEventHookExecutionId,
                    deploymentId);
            return "Failed";
        }
    }

    private void AddArguments(
            HashTree testPlanTree) {
        lambdaLogger.log("Going to add arguments" + lineSeparator);

        HashMap<String, String> arguments = GetArguments();
        if (arguments.size() > 0) {
            SearchByClass<Arguments> udvSearch = new SearchByClass<>(Arguments.class);
            testPlanTree.traverse(udvSearch);
            Collection<Arguments> udvs = udvSearch.getSearchResults();
            Arguments args = udvs.stream().findAny().orElseGet(Arguments::new);
            arguments.keySet().forEach(key -> args.addArgument(key, arguments.get(key)));
        }
        lambdaLogger.log("Arguments added" + lineSeparator);
    }

    private void AddReporting(
            HashTree testPlanTree,
            String logFile) {
        lambdaLogger.log("Going to add reporting" + lineSeparator);

        Summariser summer = null;
        String summariserName = JMeterUtils.getPropDefault("summariser.name", "summary");
        if (summariserName.length() > 0) {
            summer = new Summariser(summariserName);
        }
        ResultCollector logger = new ResultCollector(summer);
        testPlanTree.add(testPlanTree.getArray()[0], logger);
        logger.setFilename(logFile);

        lambdaLogger.log("Reporting added" + lineSeparator);
    }

    private void GenerateHtmlReport(
            String logFile) throws ConfigurationException, GenerationException {

        Boolean exportHtmlReport = Boolean.parseBoolean(System.getenv("EXPORT_JMETER_HTML"));
        if (exportHtmlReport) {
            lambdaLogger.log("Going to generate HTML report" + lineSeparator);

            JMeterUtils.setProperty("jmeter.reportgenerator.exporter.html.classname", "org.apache.jmeter.report.dashboard.HtmlTemplateExporter");
            JMeterUtils.setProperty("jmeter.reportgenerator.exporter.html.property.output_dir", "/tmp/report-genarator-html");
            JMeterUtils.setProperty("jmeter.reportgenerator.exporter.json.property.output_dir", "/tmp/report-genarator-json");
            JMeterUtils.setProperty("jmeter.reportgenerator.temp_dir", "/tmp/report-genarator-temp");

            ReportGenerator generator = new ReportGenerator(logFile, null);
            generator.generate();

            lambdaLogger.log("HTML report generated" + lineSeparator);
        }
    }

    private HashMap<String, String> GetArguments() {
        lambdaLogger.log("Going to get arguments" + lineSeparator);

        Map<String, String> environmentVariables = System.getenv();

        HashMap<String, String> arguments = new HashMap<>();

        for (String key : environmentVariables.keySet()) {
            if (key.startsWith("JMETER_VARIABLE_")) {
                String name = key.replace("JMETER_VARIABLE_", "").toLowerCase(Locale.ROOT);
                String value = environmentVariables.get(key);
                lambdaLogger.log("JMeter variable " + name + ":" + value + lineSeparator);
                arguments.put(name, value);
            }
        }

        lambdaLogger.log("Arguments retrieved" + lineSeparator);
        return arguments;
    }

    private String GetJMeterPropertiesPath(
            String path) {

        String[] elements = new String[]{
                GetJMeterPath(path),
                "bin",
                "jmeter.properties",
        };

        return String.join(File.separator, elements);
    }

    private String GetJMeterPath(
            String path) {

        String[] elements = new String[]{
                path,
                "lib",
                "apache-jmeter-5.4.3"
        };

        return String.join(File.separator, elements);
    }

    // AWS
    private void UploadToS3() {
        lambdaLogger.log("Going to upload file to S3" + lineSeparator);
        Boolean uploadToS3 = Boolean.parseBoolean(System.getenv("UPLOAD_TO_S3"));
        if (uploadToS3) {
            lambdaLogger.log("Starting report upload" + lineSeparator);
            Region region = Region.of(System.getenv("AWS_REGION"));
            lambdaLogger.log("Region: " + System.getenv("AWS_REGION") + lineSeparator);

            String bucketName = System.getenv("S3_BUCKET");
            lambdaLogger.log("Bucket name: " + bucketName + lineSeparator);
            String destinationFolder = System.getenv("S3_BUCKET_PATH");
            lambdaLogger.log("Destination folder " + destinationFolder + lineSeparator);

            S3TransferManager s3TransferManager = S3TransferManager
                    .builder()
                    .s3ClientConfiguration(b -> b
                            .region(region)
                            .targetThroughputInGbps(20.0))
                    .build();
            UploadDirectoryRequest uploadRequest = UploadDirectoryRequest.builder()
                    .sourceDirectory(Paths.get("/tmp"))
                    .bucket(bucketName)
                    .prefix(destinationFolder)
                    .build();
            DirectoryUpload directoryUpload = s3TransferManager.uploadDirectory(uploadRequest);
            CompletedDirectoryUpload completedDirectoryUpload = directoryUpload.completionFuture().join();
            completedDirectoryUpload.failedTransfers().forEach(a -> lambdaLogger.log(a.toString() + lineSeparator));
        }
        lambdaLogger.log("Files uploaded to S3" + lineSeparator);
    }

    private void SetCodeDeployStatus(
            String status,
            String lifecycleEventHookExecutionId,
            String deploymentId) {
        lambdaLogger.log("Going to set code deploy status" + lineSeparator);

        PutLifecycleEventHookExecutionStatusRequest putLifecycleEventHookExecutionStatusRequest = PutLifecycleEventHookExecutionStatusRequest
                .builder()
                .status(status)
                .deploymentId(deploymentId)
                .lifecycleEventHookExecutionId(lifecycleEventHookExecutionId)
                .build();
        lambdaLogger.log("PutLifecycleEventHookExecutionStatusRequest:" + putLifecycleEventHookExecutionStatusRequest + lineSeparator);

        Region region = Region.of(System.getenv("AWS_REGION"));
        CodeDeployClient client = CodeDeployClient
                .builder()
                .region(region)
                .build();

        PutLifecycleEventHookExecutionStatusResponse putLifecycleEventHookExecutionStatusResult = client.putLifecycleEventHookExecutionStatus(putLifecycleEventHookExecutionStatusRequest);
        lambdaLogger.log("PutLifecycleEventHookExecutionStatusResult:" + putLifecycleEventHookExecutionStatusResult + lineSeparator);

        lambdaLogger.log("Code deploy status updated" + lineSeparator);
    }
}
