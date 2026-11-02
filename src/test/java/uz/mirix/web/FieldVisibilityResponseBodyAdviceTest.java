package uz.mirix.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.mirix.annotations.audience.AudienceFilter;
import uz.mirix.annotations.audience.AudienceFilterController;
import uz.mirix.annotations.audience.AudienceFilterObject;
import uz.mirix.annotations.role.RoleVisibility;
import uz.mirix.annotations.role.RoleVisibilityController;
import uz.mirix.annotations.role.VisibleForRoles;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FieldVisibilityResponseBodyAdviceTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mvc = mockMvc(JsonMapper.builder().build());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void typedDtoReturnValueRetainsFieldsForAllowedRole() throws Exception {
        authenticate("user", null, "ROLE_ADMIN");

        mvc.perform(get("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").value("restricted"));
    }

    @Test
    void hidesRoleProtectedFieldsForOtherRoles() throws Exception {
        authenticate("user", null, "ROLE_USER");

        mvc.perform(get("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void missingAuthenticationHasNoRoles() throws Exception {
        mvc.perform(get("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void anonymousAuthenticationCannotUseGrantedRolesToRevealFields() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mvc.perform(get("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void filtersAnnotatedRecordComponents() throws Exception {
        mvc.perform(get("/roles/record"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void rejectsAnnotatedDtosThatSerializeToScalars() {
        assertThatThrownBy(() -> mvc.perform(get("/roles/scalar")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Visibility-filtered DTO must serialize to a JSON object: "
                        + ScalarDto.class.getName());
    }

    @Test
    void responseEntityKeepsStatusAndHeadersWhileFilteringBody() throws Exception {
        mvc.perform(get("/roles/entity"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Result", "preserved"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void filtersCollectionElementsAndPreservesNulls() throws Exception {
        mvc.perform(get("/roles/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[0].internalComment").doesNotExist())
                .andExpect(jsonPath("$[1]").value(nullValue()))
                .andExpect(jsonPath("$[2].internalComment").doesNotExist());
    }

    @Test
    void roleAndAudienceRestrictionsComposeOnTheSameDto() throws Exception {
        authenticate(claims(Map.of("aud", List.of("external"))), null, "ROLE_USER");

        mvc.perform(get("/combined/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internalComment").doesNotExist())
                .andExpect(jsonPath("$.audienceSecret").doesNotExist());
    }

    @Test
    void combinedControllerRetainsFieldsWhenBothRulesAllowThem() throws Exception {
        authenticate(claims(Map.of("sub", "alice")), null, "ROLE_ADMIN");

        mvc.perform(get("/combined/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalComment").value("restricted"))
                .andExpect(jsonPath("$.audienceSecret").value("audience restricted"));
    }

    @Test
    void readsAudienceFromClaimAccessorAndHonorsDisabledFilter() throws Exception {
        authenticate(claims(Map.of("aud", List.of("external"))), null);

        mvc.perform(get("/audience/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceSecret").doesNotExist())
                .andExpect(jsonPath("$.alwaysVisible").value("public"));
    }

    @Test
    void readsAudienceFromOAuth2AuthenticatedPrincipal() throws Exception {
        var principal = new DefaultOAuth2AuthenticatedPrincipal(
                "alice", Map.of("aud", List.of("external")), List.of());
        authenticate(principal, null);

        mvc.perform(get("/audience/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceSecret").doesNotExist());
    }

    @Test
    void audienceSubstringInAnUnrelatedClaimDoesNotCountAsAudience() throws Exception {
        authenticate(claims(Map.of("sub", "auditor", "description", "aud")), null);

        mvc.perform(get("/audience/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceSecret").value("audience restricted"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"password", "eyJhbGciOiJub25lIn0.eyJhdWQiOiJleHRlcm5hbCJ9."})
    void ordinaryAuthenticationDoesNotParseCredentialsAsJwt(String credentials) throws Exception {
        authenticate("auditor", credentials);

        mvc.perform(get("/audience/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceSecret").value("audience restricted"));
    }

    @Test
    void missingAuthenticationDoesNotCrashAudienceFiltering() throws Exception {
        mvc.perform(get("/audience/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceSecret").value("audience restricted"));
    }

    @Test
    void removesTheSerializedJsonPropertyName() throws Exception {
        mvc.perform(get("/roles/renamed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internal_comment").doesNotExist());
    }

    @Test
    void respectsApplicationNamingStrategyWhenRemovingFields() throws Exception {
        mvc = mockMvc(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build());

        mvc.perform(get("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.internal_comment").doesNotExist());
    }

    @Test
    void filtersAnnotatedFieldsDeclaredBySuperclass() throws Exception {
        mvc.perform(get("/roles/inherited"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.inheritedSecret").doesNotExist());
    }

    @Test
    void respectsApplicationDateSerializationConfiguration() throws Exception {
        mvc = mockMvc(JsonMapper.builder()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());

        mvc.perform(get("/roles/date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdOn[0]").value(2026))
                .andExpect(jsonPath("$.createdOn[1]").value(11))
                .andExpect(jsonPath("$.createdOn[2]").value(2))
                .andExpect(jsonPath("$.internalComment").doesNotExist());
    }

    @Test
    void leavesControllersWithoutTheOptInAnnotationUntouched() throws Exception {
        mvc.perform(get("/plain/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalComment").value("restricted"));
    }

    @Test
    void preservesGetOnlyFilteringContract() throws Exception {
        mvc.perform(post("/roles/dto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalComment").value("restricted"));
    }

    private static MockMvc mockMvc(JsonMapper mapper) {
        return MockMvcBuilders.standaloneSetup(
                        new RoleController(), new AudienceController(), new CombinedController(), new PlainController())
                .setControllerAdvice(new FieldVisibilityResponseBodyAdvice(mapper))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    private static ClaimAccessor claims(Map<String, Object> values) {
        return () -> values;
    }

    private static void authenticate(Object principal, Object credentials, String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, credentials, authorities));
    }

    @RestController
    @RoleVisibilityController
    static class RoleController {
        @GetMapping("/roles/dto")
        RoleDto dto() {
            return new RoleDto();
        }

        @GetMapping("/roles/record")
        RecordDto record() {
            return new RecordDto("Alice", "restricted");
        }

        @GetMapping("/roles/scalar")
        ScalarDto scalar() {
            return new ScalarDto();
        }

        @PostMapping("/roles/dto")
        RoleDto postDto() {
            return new RoleDto();
        }

        @GetMapping("/roles/entity")
        ResponseEntity<RoleDto> entity() {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header("X-Result", "preserved")
                    .body(new RoleDto());
        }

        @GetMapping("/roles/list")
        List<RoleDto> list() {
            return Arrays.asList(new RoleDto(), null, new RoleDto());
        }

        @GetMapping("/roles/renamed")
        RenamedDto renamed() {
            return new RenamedDto();
        }

        @GetMapping("/roles/inherited")
        InheritedDto inherited() {
            return new InheritedDto();
        }

        @GetMapping("/roles/date")
        DatedDto date() {
            return new DatedDto();
        }
    }

    @RestController
    @AudienceFilterController
    static class AudienceController {
        @GetMapping("/audience/dto")
        AudienceDto dto() {
            return new AudienceDto();
        }
    }

    @RestController
    @RoleVisibilityController
    @AudienceFilterController
    static class CombinedController {
        @GetMapping("/combined/dto")
        CombinedDto dto() {
            return new CombinedDto();
        }
    }

    @RestController
    static class PlainController {
        @GetMapping("/plain/dto")
        RoleDto dto() {
            return new RoleDto();
        }
    }

    @RoleVisibility
    static class RoleDto {
        public String name = "Alice";

        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String internalComment = "restricted";
    }

    @RoleVisibility
    record RecordDto(String name, @VisibleForRoles(roles = "ROLE_ADMIN") String internalComment) {
    }

    @RoleVisibility
    static class ScalarDto {
        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String internalComment = "restricted";

        @JsonValue
        public String value() {
            return internalComment;
        }
    }

    @AudienceFilterObject
    static class AudienceDto {
        @AudienceFilter
        public String audienceSecret = "audience restricted";

        @AudienceFilter(hideIfExists = false)
        public String alwaysVisible = "public";
    }

    @RoleVisibility
    @AudienceFilterObject
    static class CombinedDto {
        public String name = "Alice";

        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String internalComment = "restricted";

        @AudienceFilter
        public String audienceSecret = "audience restricted";
    }

    @RoleVisibility
    static class RenamedDto {
        public String name = "Alice";

        @JsonProperty("internal_comment")
        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String internalComment = "restricted";
    }

    static class BaseDto {
        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String inheritedSecret = "restricted";
    }

    @RoleVisibility
    static class InheritedDto extends BaseDto {
        public String name = "Alice";
    }

    @RoleVisibility
    static class DatedDto {
        public LocalDate createdOn = LocalDate.of(2026, 11, 2);

        @VisibleForRoles(roles = "ROLE_ADMIN")
        public String internalComment = "restricted";
    }
}
