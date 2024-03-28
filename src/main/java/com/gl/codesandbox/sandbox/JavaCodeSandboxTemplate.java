package com.gl.codesandbox.sandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.gl.codesandbox.model.ExecuteCodeRequest;
import com.gl.codesandbox.model.ExecuteCodeResponse;
import com.gl.codesandbox.model.ExecuteMessage;
import com.gl.codesandbox.model.JudgeInfo;
import com.gl.codesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.gl.codesandbox.constant.CommonConstant.CLASS_NAME;
import static com.gl.codesandbox.constant.CommonConstant.FILE_NAME;

@Slf4j
public class JavaCodeSandboxTemplate implements CodeSandbox {


    public static final Long TIME_OUT = 5000L;

    public File codeToFile(String code){
        String Dir = System.getProperty("user.dir");
        String globalCodePath = Dir + File.separator + FILE_NAME;
        if(!FileUtil.exist(globalCodePath)){
            FileUtil.mkdir(globalCodePath);
        }
        String userCodeDirPath = globalCodePath + File.separator + UUID.randomUUID();
        String userCodeFileDir = userCodeDirPath + File.separator + CLASS_NAME;
        File userCode= FileUtil.writeString(code, userCodeFileDir, StandardCharsets.UTF_8);
        return userCode;
    }

    public ExecuteMessage compileFile(File userCode) {
        String complie = String.format("javac -encoding utf-8 %s", userCode.getAbsoluteFile());
        ExecuteMessage executeMessage = null;
        try {
            Process process = Runtime.getRuntime().exec(complie);
            executeMessage = ProcessUtils.runProcessAndGetMessage(process, "编译");
            System.out.println(executeMessage);
            return executeMessage;
        } catch (IOException e) {
            throw new RuntimeException("代码编译错误");
        }
    }

    public List<ExecuteMessage> runCode(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            // todo 区分win和Linux的写法 win：%s;%s Linux：%s:%s
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
//             String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s;%s -Djava.security.manager=%s Main %s", userCodeParentPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        System.out.println("程序运行超时，已经中断");
                        runProcess.destroy();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                // ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs);
                System.out.println("代码程序执行信息：" + executeMessage);
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                throw new RuntimeException("程序执行错误" + e);
            }
        }
        return executeMessageList;
    }

    public ExecuteCodeResponse getNormalResponse(List<ExecuteMessage> executeMessageList){
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        Long maxTime = 0L;
        for(ExecuteMessage executeMessage:executeMessageList){
            String errorMessage = executeMessage.getErrorMessage();
            if(StrUtil.isNotBlank(errorMessage)){
                executeCodeResponse.setStatus(3);
                executeCodeResponse.setMessage(errorMessage);
                break;
            }
            outputList.add(executeMessage.getMessage());
            maxTime = Math.max(maxTime , executeMessage.getTime());
        }
        //执行成功
        if(outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo =new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    public Boolean delFile(File userCode){
        boolean del = true;
        if(userCode.getParentFile()!=null){
            del = FileUtil.del(userCode.getParentFile().getParentFile());
        }
        return del;
    }
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        //1.将代码保存为文件
        File userCode = this.codeToFile(code);

        //2.编译代码
        ExecuteMessage executeMessage = compileFile(userCode);

        //3.执行代码
        List<ExecuteMessage> executeMessageList = runCode(userCode, inputList);
        //4.整理输出
        ExecuteCodeResponse executeCodeResponse = getNormalResponse(executeMessageList);

        //5.文件清理
        Boolean b = delFile(userCode);
        if(b == false){
            log.error("删除文件夹失败");
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
