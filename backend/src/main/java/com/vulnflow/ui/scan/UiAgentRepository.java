package com.vulnflow.ui.scan;
import java.util.List; import org.springframework.data.jpa.repository.JpaRepository;
public interface UiAgentRepository extends JpaRepository<UiAgent,String>{List<UiAgent> findAllByOrderByLastHeartbeatAtDesc();}
