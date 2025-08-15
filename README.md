# RateLimit API Gateway

## Overview
RateLimit API Gateway — это Spring Cloud Gateway проект, реализующий:
- **Rate Limiting** — ограничение количества запросов от клиента с использованием Redis.
- **Response Caching** — кэширование ответов для ускорения обработки повторных запросов.
- Гибкую конфигурацию фильтров и маршрутов для микросервисной архитектуры.

## Features
- Ограничение количества запросов в единицу времени для защиты от перегрузки.
- Хранение данных о лимитах и кэше в Redis.
- Кэширование ответов с настраиваемым TTL.
- Расширяемая архитектура для добавления кастомных фильтров.
- Интеграция со Spring Boot и Spring Cloud Gateway.
- Логирование входящих запросов и ответов.

## Technology Stack
- **Java 17+**
- **Spring Boot**
- **Spring Cloud Gateway**
- **Redis (Reactive)**
- **Lombok**
- **Maven**

## Project Structure
```
RateLimitApiGateway/
 ├── config/               # Конфигурации Redis, фильтров и маршрутов
 ├── filter/               # Кастомные Gateway фильтры
 ├── model/                 # DTO и настройки
 ├── service/               # Логика rate limiting и caching
 ├── controller/            # Тестовые/служебные эндпоинты
 ├── application.yml        # Основные настройки
 └── pom.xml
```

## Getting Started

### Prerequisites
- **Java 17+**
- **Maven 3.8+**
- **Redis** (локально или в Docker)

### Installation
```bash
git clone https://github.com/username/RateLimitApiGateway.git
cd RateLimitApiGateway
mvn clean install
```

### Running Locally
1. Запустить Redis:
```bash
docker run --name redis -p 6379:6379 -d redis
```
2. Запустить приложение:
```bash
mvn spring-boot:run
```

### Configuration
В `application.yml` можно настроить:
- Порог запросов (`rate-limit`)
- Время жизни кэша (`cache.ttl`)

## Example Usage
Отправьте запрос:
```bash
curl http://localhost:8080/api/test
```
При превышении лимита вернётся HTTP 429 Too Many Requests.

---

## License
Этот проект лицензирован по лицензии MIT.
