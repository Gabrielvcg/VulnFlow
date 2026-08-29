package com.vulnflow.agent.client;
import java.time.Instant; import java.util.UUID;
public record AgentClaim(UUID requestId,UUID claimToken,Instant leaseExpiresAt,String targetName,String targetType,String targetReference){}
