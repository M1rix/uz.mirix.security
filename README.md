# uz.mirix.security

`uz.mirix.security` — лёгкая Spring-библиотека для скрытия отдельных полей в JSON-ответе на основе ролей пользователя (Spring Security Authorities).

Решение построено на:

- Аннотациях для маркировки DTO и контроллеров
- Spring AOP (@Aspect) для перехвата контроллеров
- Jackson ObjectMapper для удаления запрещённых полей из JSON

---

# 📌 Основная идея

Вы помечаете:

- Контроллер — как поддерживающий role-based фильтрацию
- DTO — как объект с поддержкой скрытия полей
- Поля — указывая, для каких ролей они видимы

Если у пользователя нет нужной роли — поле автоматически удаляется из JSON-ответа.

---


---

# 🔖 Аннотации

## 1️⃣ @RoleVisibility

Маркер-аннотация на уровне класса.

Используется для обозначения DTO, в котором нужно скрывать поля.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleVisibility {
}
```

## 2️⃣ @RoleVisibilityController
Маркер-аннотация для контроллера.

AOP будет работать только в контроллерах, помеченных этой аннотацией.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleVisibilityController {
}
```

## 3️⃣ @VisibleForRoles
Аннотация на уровне поля.

Определяет список ролей, которым поле разрешено.

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VisibleForRoles {
    String[] roles();
}
```
## ⚙️ AOP Механизм
Основная логика реализована в:

uz.mirix.aop.fields.RoleBasedFieldHidingAspect
Аспект:
Перехватывает методы с @GetMapping
Только внутри контроллеров с @RoleVisibilityController
Получает роли из SecurityContextHolder
Удаляет запрещённые поля из JSON через ObjectNode

```java
Pointcut
@Around(
    "@within(RoleVisibilityController) && " +
    "@annotation(GetMapping)"
)
```
⚠ Сейчас работает только для GET методов.

🔄 Поддерживаемые типы возврата
`ResponseEntity<?>`
`Одиночный объект`
`Collection<?>`

📦 Пример использования
Контроллер

```java
@RoleVisibilityController
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable Long id) {
        return userService.getUser(id);
    }
}
```
DTO
```java
@RoleVisibility
public class UserDto {

    public Long id;

    @VisibleForRoles(roles = {"ROLE_ADMIN"})
    public String internalComment;

    public String fullName;
}
```
Поведение
Пользователь с `ROLE_ADMIN`:
```java
{
  "id": 1,
  "internalComment": "secret",
  "fullName": "John Doe"
}
```
Пользователь без ROLE_ADMIN:
```java
{
  "id": 1,
  "fullName": "John Doe"
}
```
Поле `internalComment` будет удалено.
