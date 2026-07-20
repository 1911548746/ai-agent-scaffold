package com.hh.test.ai;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.runtime.AgentRuntimeRegistry;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("需要真实模型供应商，使用受保护的 live-agent-it 环境手动执行")
public class AiAgentAutoConfigTest {

    @Resource
    private AgentRuntimeRegistry runtimeRegistry;

    @Test
    public void test01() {

        RunConfig runConfig = RunConfig.builder()
//                .streamingMode(RunConfig.StreamingMode.SSE)
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        AgentRuntime runtime = runtimeRegistry.find("100000").orElseThrow();

        String appName = runtime.appName();
        Runner runner = runtime.runner();

        Session session = runner.sessionService()
                .createSession(appName, "user001")
                .blockingGet();

//        runTurnStream(runner, session, "北京今天的天气怎么样？", runConfig);
        runTurn(runner, session, "北京今天的天气怎么样？", runConfig);

    }

    @Test
    public void test02() {

        RunConfig runConfig = RunConfig.builder()
//                .streamingMode(RunConfig.StreamingMode.SSE)
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();

        AgentRuntime runtime = runtimeRegistry.find("100000").orElseThrow();

        String appName = runtime.appName();
        Runner runner = runtime.runner();

        Session session = runner.sessionService()
                .createSession(appName, "user001")
                .blockingGet();

        runTurn(runner, session, "你好", runConfig);

    }


    private static void runTurn(
            Runner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);

        Content userMessage = Content.fromParts(Part.fromText(userInput));

        String finalAnswer = runner
                .runAsync(session.userId(), session.id(), userMessage, runConfig)
                .filter(Event::finalResponse)
                .map(Event::stringifyContent)
                .blockingFirst();

        System.out.println("智能体 > " + finalAnswer);

//        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
//                .blockingForEach(event -> {
//                    if (event.finalResponse()) {
//                        System.out.println("智能体 > " + event.stringifyContent());
//                    }
//                });

    }

    private static void runTurnStream(
            Runner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        System.out.println("\n[事件流]");

        Content userMessage = Content.fromParts(Part.fromText(userInput));
        StringBuilder streamedAnswer = new StringBuilder();
        boolean[] printedTextStream = {false};

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(event -> handleEvent(event, streamedAnswer, printedTextStream));

        System.out.println("\n\n[事件流结束]");
        System.out.println("\n\n[累计得到的流式回答]");
        System.out.println(streamedAnswer);
    }

    private static void handleEvent(
            Event event,
            StringBuilder streamedAnswer,
            boolean[] printedTextStream) {

        if (!event.functionCalls().isEmpty()) {
//            System.out.println("\nfunctionCalls > " + event.functionCalls());
            return;
        }

        if (!event.functionResponses().isEmpty()) {
//            System.out.println("\nfunctionResponses > " + event.functionResponses());
            return;
        }

        String text = extractText(event);

        if (!text.isBlank()) {
            if (!printedTextStream[0]) {
                System.out.println("text chunks:");
                printedTextStream[0] = true;
            }
            streamedAnswer.append(text);
            System.out.print(text);
            System.out.flush();
        }
    }

    private static void runTurnWithEvent(
            Runner runner,
            Session session,
            String userInput,
            RunConfig runConfig) {

        System.out.println("\n用户 > " + userInput);
        Content userMessage = Content.fromParts(Part.fromText(userInput));

        runner.runAsync(session.userId(), session.id(), userMessage, runConfig)
                .blockingForEach(AiAgentAutoConfigTest::printEventSignals);
    }

    private static void printEventSignals(Event event) {
        System.out.println("\n[event]");
        System.out.println("id: " + event.id());
        System.out.println("invocationId: " + event.invocationId());
        System.out.println("author: " + event.author());
        System.out.println("timestamp: " + event.timestamp());

        String text = extractText(event);
        if (!text.isBlank()) {
            System.out.println("content: " + text);
        }

        event.partial().ifPresent(value -> System.out.println("partial: " + value));
        event.turnComplete().ifPresent(value -> System.out.println("turnComplete: " + value));
        event.finishReason().ifPresent(value -> System.out.println("finishReason: " + value));
        event.errorCode().ifPresent(value -> System.out.println("errorCode: " + value));
        event.errorMessage().ifPresent(value -> System.out.println("errorMessage: " + value));
        event.usageMetadata().ifPresent(value -> System.out.println("usageMetadata: " + value));
        event.modelVersion().ifPresent(value -> System.out.println("modelVersion: " + value));

        if (!event.functionCalls().isEmpty()) {
            System.out.println("functionCalls: " + event.functionCalls());
        }

        if (!event.functionResponses().isEmpty()) {
            System.out.println("functionResponses: " + event.functionResponses());
        }

        if (!event.actions().stateDelta().isEmpty()) {
            System.out.println("stateDelta: " + event.actions().stateDelta());
        }

        if (!event.actions().artifactDelta().isEmpty()) {
            System.out.println("artifactDelta: " + event.actions().artifactDelta());
        }

        event.actions().transferToAgent().ifPresent(value -> System.out.println("transferToAgent: " + value));
        event.actions().escalate().ifPresent(value -> System.out.println("escalate: " + value));
        event.actions().endInvocation().ifPresent(value -> System.out.println("endInvocation: " + value));

        if (event.finalResponse()) {
            System.out.println();
            System.out.println("finalResponse: true");
            System.out.println("智能体 > " + event.stringifyContent());
        }
    }


    private static String extractText(Event event) {
        return event.content()
                .map(Content::text)
                .orElse("");
    }


}
