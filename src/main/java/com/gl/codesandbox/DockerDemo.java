package com.gl.codesandbox;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;

import java.util.List;

public class DockerDemo {
    public static void main(String[] args) throws InterruptedException {
        //1.拉取镜像
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        String image = "nginx:latest";
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
        PullImageResultCallback resultCallback = new PullImageResultCallback(){
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("拉取镜像： " + item.getStatus());
                super.onNext(item);
            }
        };
        pullImageCmd.exec(resultCallback).awaitCompletion();

        //2.创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse createContainerResponse = containerCmd.withCmd("echo", "hello").exec();
        String containerId = createContainerResponse.getId();
        System.out.println("容器 id: " + containerId);

//        //3.查看容器状态
//        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd();
//        List<Container> containerList = listContainersCmd.withShowAll(true).exec();
//        for(Container container: containerList){
//            System.out.println(container.toString());
//        }
//
//        //4.启动容器
//        StartContainerCmd startContainerCmd = dockerClient.startContainerCmd(containerId);
//        startContainerCmd.exec();
//
//        //5.查看容器日志
//        LogContainerCmd logContainerCmd = dockerClient.logContainerCmd(containerId);
//        LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback(){
//            @Override
//            public void onNext(Frame item) {
//                System.out.println("容器日志：" + item.getPayload().toString());
//                super.onNext(item);
//            }
//        };
//        try {
//            logContainerCmd.
//                    withStdErr(true).
//                    withStdOut(true).
//                    exec(logContainerResultCallback).awaitCompletion();
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//
//        //6.删除容器
//        RemoveContainerCmd removeContainerCmd = dockerClient.removeContainerCmd(containerId);
//        removeContainerCmd.withForce(true).exec();
//
//        //7.删除容器
//        dockerClient.removeImageCmd(image).exec();


    }
}
