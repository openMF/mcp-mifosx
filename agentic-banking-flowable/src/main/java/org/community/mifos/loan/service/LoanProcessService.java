package org.community.mifos.loan.service;

import org.community.mifos.loan.model.LoanApplication;
import org.community.mifos.loan.model.LoanDecision;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LoanProcessService {

    public static final String PROCESS_KEY = "loanOrigination";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;

    public LoanProcessService(RuntimeService runtimeService,
                              TaskService taskService,
                              HistoryService historyService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
    }

    @Transactional
    public String startProcess(LoanApplication app) {
        // Only put Flowable-serializable values (Maps, Strings, Numbers)
        Map<String, Object> vars = new HashMap<>();
        vars.put("loanApplication", app.toMap());
        vars.put("applicantId", app.getApplicantId());
        vars.put("fullName", app.getFullName());
        vars.put("requestedAmount", app.getRequestedAmount() != null
                ? app.getRequestedAmount().toPlainString() : null);
        vars.put("status", "STARTED");

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                PROCESS_KEY, app.getWorkflowId(), vars);
        return pi.getId();
    }

    public String getStatus(String processInstanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (pi != null) {
            Object s = runtimeService.getVariable(processInstanceId, "status");
            return s != null ? s.toString() : "RUNNING";
        }
        HistoricProcessInstance h = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (h != null) {
            var hvi = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .variableName("finalStatus")
                    .singleResult();
            if (hvi != null && hvi.getValue() != null) {
                return hvi.getValue().toString();
            }
            return "COMPLETED";
        }
        return "NOT_FOUND";
    }

    @SuppressWarnings("unchecked")
    public LoanDecision getSummary(String processInstanceId) {
        Object d = getVariable(processInstanceId, "aiDecision");
        if (d instanceof Map) {
            return LoanDecision.fromMap((Map<String, Object>) d);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public LoanDecision getFinalResult(String processInstanceId) {
        LoanDecision d = getSummary(processInstanceId);
        if (d == null) return null;
        Object fs = getVariable(processInstanceId, "finalStatus");
        if (fs != null) d.setFinalStatus(fs.toString());
        Object ha = getVariable(processInstanceId, "humanAction");
        if (ha != null) d.setHumanDecision(ha.toString());
        Object fl = getVariable(processInstanceId, "fineractLoan");
        if (fl instanceof Map) {
            d.setFineractLoan((Map<String, Object>) fl);
        }
        return d;
    }

    @Transactional
    public void completeHumanReview(String processInstanceId, String action, String comments) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("humanReview")
                .singleResult();
        if (task == null) {
            throw new IllegalStateException("No human review task for process " + processInstanceId);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("humanAction", action.toUpperCase());
        vars.put("humanComments", comments != null ? comments : "");
        if (!"APPROVE".equalsIgnoreCase(action)) {
            vars.put("finalStatus", "REJECTED");
            vars.put("status", "COMPLETED");
            Object d = runtimeService.getVariable(processInstanceId, "aiDecision");
            if (d instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> decisionMap = new HashMap<>((Map<String, Object>) map);
                decisionMap.put("humanDecision", action.toUpperCase());
                decisionMap.put("finalStatus", "REJECTED");
                vars.put("aiDecision", decisionMap);
            }
        }
        taskService.complete(task.getId(), vars);
    }

    public List<Task> listOpenReviewTasks() {
        return taskService.createTaskQuery()
                .taskDefinitionKey("humanReview")
                .list();
    }

    private Object getVariable(String processInstanceId, String name) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (pi != null) {
            return runtimeService.getVariable(processInstanceId, name);
        }
        var hvi = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .variableName(name)
                .singleResult();
        return hvi != null ? hvi.getValue() : null;
    }
}
