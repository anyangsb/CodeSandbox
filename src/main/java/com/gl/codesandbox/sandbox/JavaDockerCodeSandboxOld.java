package com.gl.codesandbox.sandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.gl.codesandbox.model.ExecuteCodeRequest;
import com.gl.codesandbox.model.ExecuteCodeResponse;
import com.gl.codesandbox.model.ExecuteMessage;
import com.gl.codesandbox.model.JudgeInfo;
import com.gl.codesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.gl.codesandbox.constant.CommonConstant.CLASS_NAME;
import static com.gl.codesandbox.constant.CommonConstant.FILE_NAME;

public class JavaDockerCodeSandboxOld implements CodeSandbox {

    public static void main(String[] args) {
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();

        executeCodeRequest.setInputList(Arrays.asList("1 2","2 3"));
        String s = ResourceUtil.readStr("/home/huangl/gl-codesandbox/src/main/resources/testCode/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(s);
        executeCodeRequest.setLanguage("java");
        JavaDockerCodeSandboxOld codeSandbox = new JavaDockerCodeSandboxOld();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    private static Boolean first_init = true;

    private static final Long TIME_OUT = 5000L;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        //1.将代码保存为文件
        String Dir = System.getProperty("user.dir");
        String globalCodePath = Dir + File.separator + FILE_NAME;
        if(!FileUtil.exist(globalCodePath)){
            FileUtil.mkdir(globalCodePath);
        }
        String userCodeDirPath = globalCodePath + File.separator + UUID.randomUUID();
        String userCodeFileDir = userCodeDirPath + File.separator + CLASS_NAME;
        File userCode= FileUtil.writeString(code, userCodeFileDir, StandardCharsets.UTF_8);

        //2.编译代码
        String complie = String.format("javac -encoding utf-8 %s", userCode.getAbsoluteFile());
        try {
            Process process = Runtime.getRuntime().exec(complie);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(process, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        //3.创建容器，上传编译文件

        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        //3.1拉取java镜像
        String image = "openjdk:8-alpine";
        if(first_init){
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("拉取镜像 ：" + item.getStatus());
                    super.onNext(item);
                }

            };
            try {
                pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("拉取镜像成功");
            first_init = false;
        }

        //3.2创建并启动容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        //设置文件配置
        HostConfig hostConfig = new HostConfig();
        hostConfig.setBinds(new Bind(userCodeDirPath,new Volume("/app")));
        hostConfig.withMemory((long) 1000 * 1000 * 10);
        hostConfig.withCpuCount(1L);
        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
//                .withNetworkDisabled(true)
//                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .exec();
        String containerId = createContainerResponse.getId();

        StartContainerCmd startContainerCmd = dockerClient.startContainerCmd(containerId);
        startContainerCmd.exec();

        //4.用容器执行文件
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for(String inputArgs:inputList){
            StopWatch stopWatch = new StopWatch();
            //4.1创建执行命令
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            if(execCreateCmdResponse == null){
                throw new RuntimeException("执行命令不存在");
            }
            String execId = execCreateCmdResponse.getId();
            //4.2启动执行命令

            ExecuteMessage executeMessage = new ExecuteMessage();

            final String[] message = {executeMessage.getMessage()};
            final String[] errorMessage = {executeMessage.getErrorMessage()};
            final boolean[] timeout = {true};
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback(){
                @Override
                public void onComplete() {
                    //完成则表示没有超时
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    // 获取程序执行信息
                    //输出帧包括类型包括STDERR和STDOUT,类型是STDERR则表示有错误
                    //frame.getPayload()方法会输出帧的原始数据
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果：" + errorMessage[0]);
                    } else {
                        if(message[0] == null) {
                            message[0] = new String(frame.getPayload());
                        }else{
                            message[0]+=new String(frame.getPayload());
                        }
                        System.out.println("输出结果：" + message[0]);
                    }
                    super.onNext(frame);
                }
            };

            final Long[] memory = {0L};
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {
                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用 ：" + statistics.getMemoryStats().getUsage());
                    memory[0] = Math.max(statistics.getMemoryStats().getUsage(), memory[0]);
                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }

                @Override
                public void close() throws IOException {

                }
            });
            statsCmd.exec(statisticsResultCallback);

            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback).awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            if(timeout[0]){
                throw new RuntimeException("执行超时");
            }
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setMemory(memory[0]);
            executeMessageList.add(executeMessage);
        }

        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        Long maxTime = 0L;
        Long maxMemory = 0L;
        List<String> outputList = new ArrayList<>();
        for(ExecuteMessage executeMessage:executeMessageList){
            String errorMessage = executeMessage.getErrorMessage();
            if(StrUtil.isNotBlank(errorMessage)){
                executeCodeResponse.setStatus(3);
                executeCodeResponse.setMessage(errorMessage);
                System.out.println("执行失败");
                break;
            }
            maxTime = Math.max(maxTime,executeMessage.getTime());
            maxMemory = Math.max(maxMemory,executeMessage.getMemory());
            outputList.add(executeMessage.getMessage());
        }
        //执行成功
        if(outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        //设置到返回结果中
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo =new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        //5.文件清理
        if(userCode.getParentFile()!=null){
            boolean del = FileUtil.del(userCodeDirPath);
            System.out.println("删除" + (del?"成功":"失败"));
        }

        //6.编写错误处理
        return executeCodeResponse;
    }

    /**
     * 获取错误响应
     * @param e
     * @return
     */
    private static ExecuteCodeResponse getErrorResponse(Throwable e){
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        //代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
