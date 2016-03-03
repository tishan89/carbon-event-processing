/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.event.throttle.core.internal;

import org.apache.log4j.Logger;
import org.wso2.carbon.databridge.agent.DataPublisher;
import org.wso2.carbon.databridge.agent.exception.DataEndpointAgentConfigurationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointAuthenticationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointConfigurationException;
import org.wso2.carbon.databridge.agent.exception.DataEndpointException;
import org.wso2.carbon.databridge.commons.exception.TransportException;
import org.wso2.carbon.databridge.commons.utils.DataBridgeCommonsUtils;
import org.wso2.carbon.event.throttle.core.ThrottlerService;
import org.wso2.carbon.event.throttle.core.exception.ThrottleConfigurationException;
import org.wso2.carbon.event.throttle.core.internal.util.GlobalPolicyGenerator;
import org.wso2.carbon.event.throttle.core.internal.util.GlobalThrottleEngineClient;
import org.wso2.carbon.event.throttle.core.internal.util.ThrottleConstants;
import org.wso2.carbon.event.throttle.core.internal.util.ThrottleHelper;
import org.wso2.siddhi.core.ExecutionPlanRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.stream.input.InputHandler;
import org.wso2.siddhi.core.stream.output.StreamCallback;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttling service implementation based on WSO2 Siddhi
 */
public class CarbonThrottlerService implements ThrottlerService {
    private static final Logger log = Logger.getLogger(CarbonThrottlerService.class);

    private SiddhiManager siddhiManager;
    private InputHandler eligibilityStreamInputHandler;
    private Map<String, InputHandler> requestStreamInputHandlerMap;
    private Map<String, ExecutionPlanRuntime> ruleRuntimeMap;
    private Map<String, ResultContainer> resultMap;

    private AtomicInteger ruleCount = new AtomicInteger(0);

    private ExecutionPlanRuntime commonExecutionPlanRuntime;

    private DataPublisher dataPublisher = null;
    private String streamID;

    private GlobalThrottleEngineConfig globalThrottleEngineConfig;
    private ThrottleConfig throttleConfig;
    private GlobalPolicyGenerator globalPolicyGenerator;


    public CarbonThrottlerService() throws ThrottleConfigurationException {
        try {
            requestStreamInputHandlerMap = new ConcurrentHashMap<String, InputHandler>();
            ruleRuntimeMap = new ConcurrentHashMap<String, ExecutionPlanRuntime>();
            resultMap = new ConcurrentHashMap<String, ResultContainer>();

            throttleConfig = ThrottleHelper.loadThrottleConfig();
            globalThrottleEngineConfig = ThrottleHelper.loadCEPConfig();
            globalPolicyGenerator = new GlobalPolicyGenerator(throttleConfig);
            this.start();
        } finally {
            stop();
        }
    }

    /**
     * Starts throttler engine. Calling method should catch the exceptions and call stop to clean up.
     */
    private void start() throws ThrottleConfigurationException {
        siddhiManager = new SiddhiManager();
        ThrottleHelper.loadDataSourceConfiguration(siddhiManager);

        String commonExecutionPlan = getLocalCommonThrottleConfig();
        commonExecutionPlanRuntime = siddhiManager.createExecutionPlanRuntime(commonExecutionPlan);
        //add callback to get local throttling result and add it to ResultContainer
        commonExecutionPlanRuntime.addCallback("ThrottleStream", new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    resultMap.get(event.getData(1).toString()).addResult((String) event.getData(0), (Boolean) event.getData(2));
                }
            }
        });
        commonExecutionPlanRuntime.start();
        //get and register inputHandler
        this.eligibilityStreamInputHandler = commonExecutionPlanRuntime.getInputHandler("EligibilityStream");

        //initialize binary data publisher to send requests to global CEP instance
        initDataPublisher();
    }

    /**
     * Retrieves the common throttling configuration from file and create the common execution plan.
     *
     * @return Common execution plan used by local throttler
     * @throws ThrottleConfigurationException
     */
    private String getLocalCommonThrottleConfig() throws ThrottleConfigurationException {

        String query = throttleConfig.getRequestStream() + throttleConfig.getEligibilityStream() +
                throttleConfig.getEventTable() + throttleConfig.getLocalQuery();
        return query;
    }

    public void deployGlobalThrottlingPolicy(String name, String query) throws ThrottleConfigurationException {
        String queryPlan;
        if (name.equals(ThrottleConstants.COMMON_PLAN)) {
            //This is not used in this implementation. Common policy is deployed by default to Global Throttling Engine.
            //In a clustered deployment file will be copied manually.
            queryPlan = globalPolicyGenerator.getCommonPolicyPlan();
        } else {
            queryPlan = globalPolicyGenerator.getCustomPolicyPlan(name, query);
        }
        GlobalThrottleEngineClient globalThrottleEngineClient = new GlobalThrottleEngineClient();
        globalThrottleEngineClient.deployExecutionPlan(name, queryPlan, globalThrottleEngineConfig);
    }

    /**
     * Deploy the provided policy file and store the references for usages. Each policy will be deployed as a
     * separate ExecutionPlans and will be connected with common ExecutionPlan using call backs. Each policy will
     * return a single throttling decision. Throttling decision of a request is the aggregation of these decisions.
     *
     * @param policy
     * @throws ThrottleConfigurationException
     */
    public void deployLocalThrottlingPolicy(Policy policy) throws ThrottleConfigurationException {
        final String name = policy.getName();
        if (requestStreamInputHandlerMap.containsKey(name)) {
            undeployLocalCEPRules(name);
        }
        String eligibilityQueries = (throttleConfig.getRequestStream() + "\n" + policy.getEligibilityQuery());

        ExecutionPlanRuntime ruleRuntime = siddhiManager.createExecutionPlanRuntime(eligibilityQueries);

        //Add call backs. Here, we take output events and insert into EligibilityStream
        ruleRuntime.addCallback("EligibilityStream", new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                try {
                    eligibilityStreamInputHandler.send(events);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Error occurred when publishing to EligibilityStream of throttling policy " + name, e);
                }
            }
        });

        ruleRuntime.start();

        //get and register input handler for RequestStream, so isThrottled() can use it.

        requestStreamInputHandlerMap.put(name, ruleRuntime.getInputHandler("RequestStream"));
        ruleRuntimeMap.put(name, ruleRuntime);
        ruleCount.incrementAndGet();

    }

    /**
     * Undeploy the throttling policy with given name if already deployed.     *
     * @param policyName
     */
    public void undeployLocalCEPRules(String policyName) {
        ExecutionPlanRuntime ruleRuntime = ruleRuntimeMap.get(policyName);
        if (ruleRuntime != null) {
            ruleCount.decrementAndGet();
            requestStreamInputHandlerMap.remove(policyName);
            ruleRuntime.shutdown();
            ruleRuntimeMap.remove(policyName);
        }

    }


    /**
     * Returns whether the given throttleRequest is throttled.
     *
     * @param throttleRequest User throttleRequest to APIM which needs to be checked whether throttled
     * @return Throttle status for current throttleRequest
     */
    public boolean isThrottled(Object[] throttleRequest) {
        if (ruleCount.get() != 0) {
            String uniqueKey = (String) throttleRequest[0];
            ResultContainer result = new ResultContainer(ruleCount.get());
            resultMap.put(uniqueKey.toString(), result);
            for (InputHandler inputHandler : requestStreamInputHandlerMap.values()) {
                try {
                    inputHandler.send(Arrays.copyOf(throttleRequest, throttleRequest.length));
                } catch (InterruptedException e) {
                    //interrupt current thread so that interrupt can propagate
                    Thread.currentThread().interrupt();
                    log.error(e.getMessage(), e);
                }
            }
            //Blocked call to return synchronous result
            boolean isThrottled = false;
            try {
                isThrottled = result.isThrottled();
                if (log.isDebugEnabled()) {
                    log.debug("Throttling status for request to API " + throttleRequest[2] + " is " + isThrottled);
                }
            } catch (InterruptedException e) {
                //interrupt current thread so that interrupt can propagate
                Thread.currentThread().interrupt();
                log.error(e.getMessage(), e);
            }
            if (!isThrottled) {
                //Converting properties map into json compatible String
                if (throttleRequest[6] != null) {
                    throttleRequest[6] = (throttleRequest[6]).toString();
                }
                //Only send served throttleRequest to global throttler
                sendToGlobalThrottler(throttleRequest);
            }
            resultMap.remove(uniqueKey);
            return isThrottled;
        } else {
            return false;
        }
    }

    public void stop() {
        if (siddhiManager != null) {
            siddhiManager.shutdown();
        }
        if (commonExecutionPlanRuntime != null) {
            commonExecutionPlanRuntime.shutdown();
        }
    }


    private InputHandler getEligibilityStreamInputHandler() {
        return eligibilityStreamInputHandler;
    }

    private void sendToGlobalThrottler(Object[] throttleRequest) {
        org.wso2.carbon.databridge.commons.Event event = new org.wso2.carbon.databridge.commons.Event(streamID,
                System.currentTimeMillis(), null, null, throttleRequest);
        dataPublisher.tryPublish(event);
    }

    //todo exception handling
    private void initDataPublisher() {
        try {
            dataPublisher = new DataPublisher("Binary", "tcp://" + globalThrottleEngineConfig.getHostname() + ":" +
                    globalThrottleEngineConfig.getBinaryTCPPort(), "ssl://" + globalThrottleEngineConfig.getHostname() +
                    ":" + globalThrottleEngineConfig.getBinarySSLPort(), globalThrottleEngineConfig.getUsername(),
                    globalThrottleEngineConfig.getPassword());
            streamID = DataBridgeCommonsUtils.generateStreamId(throttleConfig.getRequestStreamID().split(":")[0],
                    throttleConfig.getRequestStreamID().split(":")[1]);
        } catch (DataEndpointAgentConfigurationException e) {
            log.error("Error in initializing binary data-publisher to send requests to global throttling engine " +
                    e.getMessage(), e);
        } catch (DataEndpointException e) {
            log.error("Error in initializing binary data-publisher to send requests to global throttling engine " +
                    e.getMessage(), e);
        } catch (DataEndpointConfigurationException e) {
            log.error("Error in initializing binary data-publisher to send requests to global throttling engine " +
                    e.getMessage(), e);
        } catch (DataEndpointAuthenticationException e) {
            log.error("Error in initializing binary data-publisher to send requests to global throttling engine " +
                    e.getMessage(), e);
        } catch (TransportException e) {
            log.error("Error in initializing binary data-publisher to send requests to global throttling engine " +
                    e.getMessage(), e);
        }


    }
}