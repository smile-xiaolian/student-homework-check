package com.edu.submission.course.adapter.in;

import com.edu.submission.course.adapter.in.dto.AddMemberRequest;
import com.edu.submission.course.adapter.in.dto.CourseCreateRequest;
import com.edu.submission.course.adapter.in.dto.CourseMemberView;
import com.edu.submission.course.adapter.in.dto.CourseUpdateRequest;
import com.edu.submission.course.adapter.in.dto.CourseView;
import com.edu.submission.course.adapter.in.dto.JoinCourseRequest;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.shared.api.Etags;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.security.CurrentUser;
import com.edu.submission.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public PageResult<CourseView> list(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return courseService.list(CurrentUser.require(), page, size);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<CourseView> create(@Valid @RequestBody CourseCreateRequest request) {
        UserPrincipal principal = CurrentUser.require();
        Course course = courseService.create(principal, request);
        CourseView view = CourseView.from(course, true);
        return ResponseEntity.created(URI.create("/api/v1/courses/" + course.getId()))
                .header(HttpHeaders.ETAG, Etags.format(view.rowVersion()))
                .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CourseView> get(@PathVariable UUID id) {
        CourseView view = courseService.get(CurrentUser.require(), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<CourseView> update(@PathVariable UUID id,
                                             @Valid @RequestBody CourseUpdateRequest request,
                                             @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        CourseView view = courseService.update(CurrentUser.require(), id, request, Etags.parse(ifMatch));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<CourseView> archive(@PathVariable UUID id,
                                              @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        CourseView view = courseService.archive(CurrentUser.require(), id, Etags.parse(ifMatch));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }

    @PostMapping("/join")
    @PreAuthorize("hasRole('STUDENT')")
    public CourseView join(@Valid @RequestBody JoinCourseRequest request) {
        return courseService.join(CurrentUser.require(), request.inviteCode());
    }

    @GetMapping("/{id}/members")
    public List<CourseMemberView> members(@PathVariable UUID id) {
        return courseService.members(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public CourseMemberView addMember(@PathVariable UUID id,
                                      @Valid @RequestBody AddMemberRequest request) {
        return courseService.addMember(CurrentUser.require(), id, request);
    }

    @PostMapping("/{id}/invite-code/rotate")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<CourseView> rotateInviteCode(@PathVariable UUID id) {
        CourseView view = courseService.rotateInviteCode(CurrentUser.require(), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, Etags.format(view.rowVersion())).body(view);
    }
}

