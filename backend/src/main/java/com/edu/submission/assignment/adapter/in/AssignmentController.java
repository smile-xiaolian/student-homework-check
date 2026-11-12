package com.edu.submission.assignment.adapter.in;

import com.edu.submission.assignment.adapter.in.dto.AssignmentCreateRequest;
import com.edu.submission.assignment.adapter.in.dto.AssignmentUpdateRequest;
import com.edu.submission.assignment.adapter.in.dto.AssignmentView;
import com.edu.submission.assignment.application.AssignmentService;
import com.edu.submission.shared.api.Etags;
import com.edu.submission.shared.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping("/courses/{courseId}/assignments")
    public List<AssignmentView> list(@PathVariable UUID courseId) {
        return assignmentService.list(CurrentUser.require(), courseId);
    }

    @PostMapping("/courses/{courseId}/assignments")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<AssignmentView> create(@PathVariable UUID courseId,
                                                 @Valid @RequestBody AssignmentCreateRequest request) {
        AssignmentView view = assignmentService.create(CurrentUser.require(), courseId, request);
        return ResponseEntity.created(URI.create("/api/v1/assignments/" + view.id()))
                .header(HttpHeaders.ETAG, Etags.format(view.rowVersion()))
                .body(view);
    }

    @GetMapping("/assignments/{id}")
    public ResponseEntity<AssignmentView> get(@PathVariable UUID id) {
        AssignmentView view = assignmentService.get(CurrentUser.require(), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @PatchMapping("/assignments/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<AssignmentView> update(@PathVariable UUID id,
                                                 @Valid @RequestBody AssignmentUpdateRequest request,
                                                 @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        AssignmentView view = assignmentService.update(CurrentUser.require(), id, request, Etags.parse(ifMatch));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @DeleteMapping("/assignments/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        assignmentService.delete(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assignments/{id}/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<AssignmentView> publish(@PathVariable UUID id) {
        AssignmentView view = assignmentService.publish(CurrentUser.require(), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @GetMapping("/assignments/{id}/check-config")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public JsonNode getCheckConfig(@PathVariable UUID id) {
        return assignmentService.getCheckConfig(CurrentUser.require(), id);
    }

    @PutMapping("/assignments/{id}/check-config")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public JsonNode putCheckConfig(@PathVariable UUID id,
                                   @RequestBody JsonNode config,
                                   @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return assignmentService.putCheckConfig(CurrentUser.require(), id, config, Etags.parse(ifMatch));
    }

    @GetMapping("/assignments/{id}/rubric")
    public JsonNode getRubric(@PathVariable UUID id) {
        return assignmentService.getRubric(CurrentUser.require(), id);
    }

    @PutMapping("/assignments/{id}/rubric")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public JsonNode putRubric(@PathVariable UUID id,
                              @RequestBody JsonNode rubric,
                              @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return assignmentService.putRubric(CurrentUser.require(), id, rubric, Etags.parse(ifMatch));
    }
}

