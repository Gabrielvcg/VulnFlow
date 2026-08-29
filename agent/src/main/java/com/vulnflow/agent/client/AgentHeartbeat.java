package com.vulnflow.agent.client;
import java.util.UUID;
public record AgentHeartbeat(String status,UUID currentRequestId,UUID claimToken,int outboxPending,int outboxDeadLetters,long outboxBytes,long diskFreeBytes,String safeError){}
