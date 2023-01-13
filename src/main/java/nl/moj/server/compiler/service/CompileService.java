/*
   Copyright 2020 First Eight BV (The Netherlands)
 

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file / these files except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.moj.server.compiler.service;

import javax.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.moj.common.messages.JMSCompileRequest;
import nl.moj.common.messages.JMSCompileResponse;
import nl.moj.common.messages.JMSFile;
import nl.moj.server.compiler.model.CompileAttempt;
import nl.moj.server.compiler.repository.CompileAttemptRepository;
import nl.moj.server.message.service.MessageService;
import nl.moj.server.runtime.model.AssignmentStatus;
import nl.moj.server.runtime.repository.AssignmentStatusRepository;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompileService {

    private final CompileAttemptRepository compileAttemptRepository;
    private final AssignmentStatusRepository assignmentStatusRepository;
    private final JmsTemplate jmsTemplate;
    private final MessageService messageService;

    public void receiveCompileResponse(JMSCompileResponse compileResponse) {
        log.info("Received compile attempt response {}", compileResponse.getAttempt());
        CompileAttempt compileAttempt = registerCompileResponse(compileResponse);
        messageService.sendCompileFeedback(compileAttempt.getAssignmentStatus().getTeam(), compileResponse);
    }

    @Transactional
    public CompileAttempt registerCompileRequest(CompileRequest compileRequest) {
        CompileAttempt compileAttempt = prepareCompileAttempt(compileRequest);
        // send JMS compile request
        jmsTemplate.convertAndSend("compile_request", JMSCompileRequest.builder()
                .attempt(compileAttempt.getUuid())
                .assignment(compileRequest.getAssignment().getUuid())
                .sources(compileRequest.getSources().entrySet().stream().map(e -> JMSFile.builder()
                        .path(e.getKey().toString())
                        .content(e.getValue())
                        .build()).collect(Collectors.toList()))
                .build());

        return compileAttempt;
    }

    @Transactional
    public CompileAttempt prepareCompileAttempt(CompileRequest compileRequest) {
        AssignmentStatus as = assignmentStatusRepository.findByAssignment_IdAndCompetitionSession_IdAndTeam_Id(
                compileRequest.getAssignment().getId(), compileRequest.getSession().getId(),
                compileRequest.getTeam().getId());

        CompileAttempt compileAttempt = CompileAttempt.builder()
                .assignmentStatus(as)
                .uuid(UUID.randomUUID())
                .dateTimeRegister(Instant.now())
                .build();
        as.getCompileAttempts().add(compileAttempt);
        return compileAttemptRepository.save(compileAttempt);
    }

    @Transactional
    public CompileAttempt registerCompileResponse(JMSCompileResponse compileResponse) {
        CompileAttempt compileAttempt = compileAttemptRepository.findByUuid(compileResponse.getAttempt());
        return update(compileAttempt, compileResponse);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public CompileAttempt update(CompileAttempt compileAttempt, JMSCompileResponse compileResponse) {

        if (compileAttempt == null) {
            return null;
        }
        if (compileResponse == null) {
            return compileAttempt;
        }

        compileAttempt.setWorker(compileResponse.getWorker());
        compileAttempt.setRun(compileResponse.getRunId());
        compileAttempt.setDateTimeStart(compileResponse.getStarted());
        compileAttempt.setDateTimeEnd(compileResponse.getEnded());
        compileAttempt.setSuccess(compileResponse.isSuccess());
        compileAttempt.setTimeout(compileResponse.isTimeout());
        compileAttempt.setCompilerOutput(compileResponse.getOutput());
        compileAttempt.setAborted(compileResponse.isAborted());
        compileAttempt.setReason(compileResponse.getReason());
        return compileAttemptRepository.save(compileAttempt);
    }
}
