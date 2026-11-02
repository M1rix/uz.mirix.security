# uz.mirix.security [![JitPack](https://jitpack.io/v/M1rix/uz.mirix.security.svg)](https://jitpack.io/#M1rix/uz.mirix.security)

Spring MVC-библиотека для скрытия полей JSON-ответа по ролям пользователя и наличию claim `aud` в его аутентификации.

## Требования

- Java 17 LTS.
- Spring Boot 3.5.16, Spring Framework 6.2 и Spring Security 6.5.
- Jackson 2 (`com.fasterxml.jackson.databind`).

Проект рассчитан на Spring Boot 3.5.x. Версии Spring Framework, Spring Security и Jackson управляются родительским POM Spring Boot. См. [документацию Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/index.html).

## Подключение

Зарегистрируйте обработчик в конфигурации приложения:

```java
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uz.mirix.web.FieldVisibilityResponseBodyAdvice;

@Configuration(proxyBeanMethods = false)
@Import(FieldVisibilityResponseBodyAdvice.class)
public class FieldVisibilityConfiguration {
}
```

Если приложение уже сканирует пакет `uz.mirix`, обработчик обнаружится автоматически. Используйте один из этих способов регистрации.

Библиотека использует настроенный приложением Jackson `ObjectMapper` и не меняет его конфигурацию.

## Фильтрация по ролям

Пометьте контроллер аннотацией `@RoleVisibilityController`, DTO — `@RoleVisibility`, а ограниченные поля — `@VisibleForRoles`:

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.mirix.annotations.role.RoleVisibility;
import uz.mirix.annotations.role.RoleVisibilityController;
import uz.mirix.annotations.role.VisibleForRoles;

@RoleVisibility
record UserDto(
        Long id,
        String fullName,
        @VisibleForRoles(roles = {"ROLE_ADMIN"}) String internalComment
) {
}

@RoleVisibilityController
@RestController
@RequestMapping("/api/users")
class UserController {

    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable Long id) {
        return new UserDto(id, "John Doe", "secret");
    }
}
```

Достаточно одной из перечисленных authorities. Имена сравниваются точно: `ROLE_ADMIN` и `ADMIN` — разные значения. При отсутствии аутентификации набор ролей считается пустым, поэтому ограниченные поля скрываются.

Ответ с `ROLE_ADMIN`:

```json
{"id": 1, "fullName": "John Doe", "internalComment": "secret"}
```

Ответ без этой роли:

```json
{"id": 1, "fullName": "John Doe"}
```

## Фильтрация по audience

Используйте `@AudienceFilterController` на контроллере, `@AudienceFilterObject` на DTO и `@AudienceFilter` на поле:

```java
import uz.mirix.annotations.audience.AudienceFilter;
import uz.mirix.annotations.audience.AudienceFilterObject;

@AudienceFilterObject
record AccountDto(
        Long id,
        @AudienceFilter String internalComment
) {
}
```

При наличии ключа `aud` поле скрывается. Обработчик читает claims уже проверенного principal: `ClaimAccessor` (например, Spring Security `Jwt`) или `OAuth2AuthenticatedPrincipal`. JWT из `credentials` не разбирается. Обычная аутентификация и отсутствующий principal считаются аутентификацией без audience.

`@AudienceFilter(hideIfExists = false)` отключает скрытие поля по audience, сохраняя прежнюю семантику. Для совместной проверки ролей и audience добавьте обе маркерные аннотации на контроллер и DTO. Поле скрывается, если его запрещает любая из включённых проверок.

## Обработка ответов

Единый `FieldVisibilityResponseBodyAdvice` обрабатывает JSON-ответы на HTTP GET перед записью тела ответа. Поддерживаются:

- DTO и records с соответствующей маркерной аннотацией;
- коллекции объектов;
- `ResponseEntity<?>` с сохранением HTTP-статуса и заголовков;
- имена свойств Jackson, включая `@JsonProperty`, naming strategy и унаследованные поля.

Контроллер может объявлять конкретный тип DTO: обработка выполняется после возврата из метода. Для других HTTP-методов и контроллеров без маркерных аннотаций фильтрация не применяется.

Помеченный DTO должен сериализоваться в JSON-объект. Скалярное или массивное представление такого DTO (например, через `@JsonValue`) отклоняется, чтобы ограничения не пропускались.

## Миграция с Spring Boot 2

Обновите приложение до Spring Boot 3.5 и Java 17. Jackson Databind сохраняет пакет `com.fasterxml.jackson.databind`. Аннотации библиотеки сохранили свои пакеты. Старые классы `RoleBasedFieldHidingAspect` и `AudienceBasedFieldHidingAspect` заменены единым `FieldVisibilityResponseBodyAdvice`; обновите их явные импорты в конфигурации. AspectJ и `@EnableAspectJAutoProxy` для этой библиотеки больше не требуются.

## Сборка

Установите JDK 17 и укажите его в `JAVA_HOME`. Maven Wrapper загрузит нужную версию Maven при первом запуске.

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```sh
sh mvnw clean verify
```
