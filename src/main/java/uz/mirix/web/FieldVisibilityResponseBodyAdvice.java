package uz.mirix.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.mirix.annotations.audience.AudienceFilter;
import uz.mirix.annotations.audience.AudienceFilterController;
import uz.mirix.annotations.audience.AudienceFilterObject;
import uz.mirix.annotations.role.RoleVisibility;
import uz.mirix.annotations.role.RoleVisibilityController;
import uz.mirix.annotations.role.VisibleForRoles;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies both visibility rules after the controller has returned its declared DTO type. */
@ControllerAdvice(annotations = {RoleVisibilityController.class, AudienceFilterController.class})
public class FieldVisibilityResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final JsonMapper objectMapper;

    public FieldVisibilityResponseBodyAdvice(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || request.getMethod() != HttpMethod.GET) {
            return body;
        }

        Class<?> controllerType = returnType.getContainingClass();
        boolean filterRoles = AnnotatedElementUtils.hasAnnotation(controllerType, RoleVisibilityController.class);
        boolean filterAudience = AnnotatedElementUtils.hasAnnotation(controllerType, AudienceFilterController.class);
        if (!filterRoles && !filterAudience) {
            return body;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        Set<String> roles = authenticated
                ? authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet())
                : Set.of();
        boolean hasAudience = authenticated && hasAudience(authentication.getPrincipal());

        return filter(body, filterRoles, filterAudience, roles, hasAudience);
    }

    private boolean hasAudience(Object principal) {
        if (principal instanceof ClaimAccessor claims) {
            return claims.getClaims().containsKey("aud");
        }
        if (principal instanceof OAuth2AuthenticatedPrincipal oauth2Principal) {
            return oauth2Principal.getAttributes().containsKey("aud");
        }
        return false;
    }

    private Object filter(Object body, boolean filterRoles, boolean filterAudience,
                          Set<String> roles, boolean hasAudience) {
        if (body == null) {
            return null;
        }
        if (body instanceof Collection<?> collection) {
            return collection.stream()
                    .map(item -> filter(item, filterRoles, filterAudience, roles, hasAudience))
                    .toList();
        }

        Class<?> bodyType = body.getClass();
        boolean roleObject = filterRoles && bodyType.isAnnotationPresent(RoleVisibility.class);
        boolean audienceObject = filterAudience && bodyType.isAnnotationPresent(AudienceFilterObject.class);
        if (!roleObject && !audienceObject) {
            return body;
        }

        var tree = objectMapper.valueToTree(body);
        if (!(tree instanceof ObjectNode objectNode)) {
            throw new IllegalStateException("Visibility-filtered DTO must serialize to a JSON object: "
                    + bodyType.getName());
        }

        var config = objectMapper.serializationConfig();
        var introspector = config.classIntrospectorInstance().forOperation(config);
        var javaType = objectMapper.constructType(bodyType);
        var classAnnotations = introspector.introspectClassAnnotations(javaType);
        var description = introspector.introspectForSerialization(javaType, classAnnotations);
        for (BeanPropertyDefinition property : description.findProperties()) {
            var field = property.getField();
            if (field == null) {
                continue;
            }

            VisibleForRoles visibility = roleObject ? field.getAnnotation(VisibleForRoles.class) : null;
            AudienceFilter audience = audienceObject ? field.getAnnotation(AudienceFilter.class) : null;
            boolean hiddenByRole = visibility != null
                    && Arrays.stream(visibility.roles()).noneMatch(roles::contains);
            boolean hiddenByAudience = audience != null && audience.hideIfExists() && hasAudience;
            if (hiddenByRole || hiddenByAudience) {
                objectNode.remove(property.getName());
            }
        }
        return objectNode;
    }
}
