package com.vulnflow.ui.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name="ui_agents")
public class UiAgent {
    @Id @Column(length=100) private String id;
    @Column(nullable=false,length=16) private String status;
    @Column(name="current_request_id") private java.util.UUID currentRequestId;
    @Column(name="outbox_pending",nullable=false) private int outboxPending;
    @Column(name="outbox_dead_letters",nullable=false) private int outboxDeadLetters;
    @Column(name="outbox_bytes",nullable=false) private long outboxBytes;
    @Column(name="disk_free_bytes",nullable=false) private long diskFreeBytes;
    @Column(name="last_error",length=500) private String lastError;
    @Column(name="last_heartbeat_at",nullable=false) private Instant lastHeartbeatAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected UiAgent() {}
    public UiAgent(String id){this.id=id; this.status="IDLE"; this.lastHeartbeatAt=Instant.now(); this.updatedAt=lastHeartbeatAt;}
    public void heartbeat(String status, java.util.UUID requestId, int pending, int dead, long bytes, long disk, String error){
        this.status=status; currentRequestId=requestId; outboxPending=Math.max(0,pending); outboxDeadLetters=Math.max(0,dead);
        outboxBytes=Math.max(0,bytes); diskFreeBytes=Math.max(0,disk); lastError=limit(error); lastHeartbeatAt=Instant.now(); updatedAt=lastHeartbeatAt;
    }
    private String limit(String v){return v==null||v.length()<=500?v:v.substring(0,500);}
    public String getId(){return id;} public String getStatus(){return status;} public java.util.UUID getCurrentRequestId(){return currentRequestId;}
    public int getOutboxPending(){return outboxPending;} public int getOutboxDeadLetters(){return outboxDeadLetters;}
    public long getOutboxBytes(){return outboxBytes;} public long getDiskFreeBytes(){return diskFreeBytes;}
    public String getLastError(){return lastError;} public Instant getLastHeartbeatAt(){return lastHeartbeatAt;}
}
