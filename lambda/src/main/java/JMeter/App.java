package JMeter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.PutLifecycleEventHookExecutionStatusRequest;
import software.amazon.awssdk.services.codedeploy.model.PutLifecycleEventHookExecutionStatusResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.transfer.s3.*;

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

import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static software.amazon.awssdk.transfer.s3.SizeConstant.MB;

public class App implements RequestHandler<Map<String, String>, String> {

    private LambdaLogger lambdaLogger;
    private String lineSeparator = System.lineSeparator();

    private static final String localBasePath = "/tmp";
    private static final String htmlReportPath = localBasePath + "/report-genarator-html";
    private static final String jsonReportPath = localBasePath + "/report-genarator-json";
    private static final String tempReportPath = localBasePath + "/report-genarator-temp";
    private static final String reportPath = localBasePath + "/result.jtl";

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
            Region region = Region.of(System.getenv("AWS_REGION"));
            lambdaLogger.log("Region: " + System.getenv("AWS_REGION") + lineSeparator);

            String bucketName = System.getenv("S3_BUCKET");
            lambdaLogger.log("Bucket name:" + bucketName + lineSeparator);
            if(bucketName == null || bucketName.trim().equals("")){
                NotifyCodeDeploy(
                        "Failed",
                        lifecycleEventHookExecutionId,
                        deploymentId);
                return "Failed";
            }

            String bucketBasePath = System.getenv("S3_BUCKET_PATH");
            lambdaLogger.log("Bucket base path:" + bucketBasePath + lineSeparator);
            if(bucketBasePath == null || bucketBasePath.trim().equals("")){
                NotifyCodeDeploy(
                        "Failed",
                        lifecycleEventHookExecutionId,
                        deploymentId);
                return "Failed";
            }

            String testPath = bucketBasePath + "/tests";
            lambdaLogger.log("Bucket tests path:" + testPath + lineSeparator);

            String testFileName = System.getenv("JMETER_LOADTEST_FILE");
            lambdaLogger.log("Test file name:" + testFileName + lineSeparator);
            if(testFileName == null || testFileName.trim().equals("")){
                NotifyCodeDeploy(
                        "Failed",
                        lifecycleEventHookExecutionId,
                        deploymentId);
                return "Failed";
            }
            String testFilePath = DownloadFileFromS3(
                    region,
                    bucketName,
                    testPath,
                    testFileName);

            String userFileName = System.getenv("JMETER_USERS_FILE");
            lambdaLogger.log("Users file name:" + userFileName + lineSeparator);
            if(userFileName == null || userFileName.trim().equals("")){
                NotifyCodeDeploy(
                        "Failed",
                        lifecycleEventHookExecutionId,
                        deploymentId);
                return "Failed";
            }
            String _ = DownloadFileFromS3(
                    region,
                    bucketName,
                    testPath,
                    userFileName);

            String taskPath = System.getenv("LAMBDA_TASK_ROOT") == null ? System.getProperty("user.dir") : System.getenv("LAMBDA_TASK_ROOT");
            lambdaLogger.log("Path:" + taskPath + lineSeparator);

            StandardJMeterEngine jMeter = new StandardJMeterEngine();
            lambdaLogger.log("Created jMeter" + lineSeparator);

            String jMeterPropertiesPath = taskPath + "/lib/apache-jmeter-5.4.3/bin/jmeter.properties";
            lambdaLogger.log("JMeterPropertiesPath:" + jMeterPropertiesPath + lineSeparator);
            JMeterUtils.loadJMeterProperties(jMeterPropertiesPath);

            String jMeterPath = taskPath + "/lib/apache-jmeter-5.4.3";
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
            File testFile = new File(testFilePath);
            lambdaLogger.log("Created loadTestFile" + lineSeparator);
            HashTree testPlanTree = SaveService.loadTree(testFile);
            lambdaLogger.log("Loaded testPlanTree" + lineSeparator);
            AddReporting(testPlanTree, reportPath);
            AddArguments(testPlanTree);

            jMeter.configure(testPlanTree);
            lambdaLogger.log("Configured jMeter" + lineSeparator);
            jMeter.run();
            lambdaLogger.log("Executed jMeter" + lineSeparator);

            GenerateHtmlReport(reportPath);
            UploadHtmlReportToS3(
                    region,
                    bucketName,
                    bucketBasePath);

            NotifyCodeDeploy(
                    "Succeeded",
                    lifecycleEventHookExecutionId,
                    deploymentId);
            return "Succeeded";
        } catch (Exception exception) {
            lambdaLogger.log("Exception occurred" + lineSeparator);
            lambdaLogger.log(exception + lineSeparator);

            NotifyCodeDeploy(
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
        lambdaLogger.log("Going to generate HTML report" + lineSeparator);

        JMeterUtils.setProperty("jmeter.reportgenerator.exporter.html.classname", "org.apache.jmeter.report.dashboard.HtmlTemplateExporter");
        JMeterUtils.setProperty("jmeter.reportgenerator.exporter.html.property.output_dir", htmlReportPath);
        JMeterUtils.setProperty("jmeter.reportgenerator.exporter.json.property.output_dir", jsonReportPath);
        JMeterUtils.setProperty("jmeter.reportgenerator.temp_dir", tempReportPath);

        ReportGenerator generator = new ReportGenerator(logFile, null);
        generator.generate();

        lambdaLogger.log("HTML report generated" + lineSeparator);
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

    // AWS
    private void UploadHtmlReportToS3(
            Region region,
            String bucketName,
            String bucketBasePath) {
        lambdaLogger.log("Going to upload file to S3" + lineSeparator);

        S3TransferManager s3TransferManager = S3TransferManager
                .builder()
                .s3ClientConfiguration(b -> b
                        .region(region)
                        .targetThroughputInGbps(20.0))
                .build();
        UploadDirectoryRequest uploadRequest = UploadDirectoryRequest.builder()
                .sourceDirectory(Paths.get(htmlReportPath))
                .bucket(bucketName)
                .prefix(bucketBasePath)
                .build();
        DirectoryUpload directoryUpload = s3TransferManager.uploadDirectory(uploadRequest);
        CompletedDirectoryUpload completedDirectoryUpload = directoryUpload.completionFuture().join();
        completedDirectoryUpload.failedTransfers().forEach(a -> lambdaLogger.log(a.toString() + lineSeparator));
        lambdaLogger.log("Files uploaded to S3" + lineSeparator);
    }

    private String DownloadFileFromS3(
            Region region,
            String bucketName,
            String bucketBasePath,
            String fileName){
        lambdaLogger.log("Going to download file:" + fileName + lineSeparator);
        S3TransferManager s3TransferManager = S3TransferManager
                .builder()
                .s3ClientConfiguration(b -> b
                        .region(region)
                        .minimumPartSizeInBytes(10 * MB)
                        .targetThroughputInGbps(20.0))
                .build();

        String path = localBasePath + "/" + fileName;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(bucketBasePath + "/" + fileName)
                .build();
        DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                .destination(Paths.get(path))
                .getObjectRequest(getObjectRequest)
                .build();

        CompletedFileDownload completedDownload = s3TransferManager.downloadFile(downloadRequest).completionFuture().join();
        lambdaLogger.log("SDK HTTP status code:" + completedDownload.response().toString() + lineSeparator);
        lambdaLogger.log("SDK HTTP status code:" + completedDownload.response().sdkHttpResponse().toString() + lineSeparator);
        lambdaLogger.log("Downloaded file:" + fileName + lineSeparator);
        return localBasePath + "/" + fileName;
    }

    private void NotifyCodeDeploy(
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
