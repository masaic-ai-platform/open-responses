
# 🚀 Masaic Dev Platform Deployment Guide

This guide outlines the steps to deploy the components required to run the **Masaic Dev Platform** locally or on a VM. Components included:

1. MongoDB
2. Qdrant
3. Open Responses (Spring Boot)
4. Platform UI (Node.js)
5. SigNoz (Telemetry/Monitoring)

---

## 1. 🗃️ MongoDB Deployment

### Option A: Using Docker
```bash
docker run -d \
  --name mongo \
  -p 27017:27017 \
  -v mongodata:/data/db \
  mongo:6
```

> 💡 Use connection string: `mongodb://localhost:27017`

---

## 2. 🧠 Qdrant Deployment

### Option A: Using Docker
```bash
docker run -d \
  --name qdrant \
  -p 6333:6333 \
  -v qdrant_data:/qdrant/storage \
  qdrant/qdrant
```

> 🟢 Host: `localhost`  
> 🟢 Port: `6333`

---

## 3. ⚙️ Open Responses Backend

### Prerequisites:
- Java 21
- Gradle
- MongoDB URI and Qdrant host/port
- OpenAI API key

### Step-by-step:

#### A. Clone and Build
```bash
git clone https://github.com/masaic-ai-platform/open-responses.git
cd open-responses
./gradlew bootJar
```

#### B. Set Required Environment Variables
```bash
OPENAI_API_KEY=your-openai-key
SPRING_PROFILES_ACTIVE=platform
OPEN_RESPONSES_STORE_VECTOR_SEARCH_QDRANT_HOST=qdrant_host
OPEN_RESPONSES_STORE_VECTOR_SEARCH_QDRANT_HOST_APIKEY=qdrant_apikey
OPEN_RESPONSES_STORE_VECTOR_SEARCH_QDRANT_HOST_USETLS=true (if secure)
OPEN_RESPONSES_MONGODB_URI=mongodb://localhost:27017
OTEL_SDK_DISABLED=false
OTEL_EXPORTER_OTLP_ENDPOINT=exporter_endpoint
OTEL_EXPORTER_OTLP_HEADERS=exporter_key
````

#### C. Run the Backend
```bash
java -jar open-responses-server/build/libs/openresponses-*.jar
```

---

## 4. 💻 Platform UI (Frontend)

### Prerequisites:
- Node.js 18+
- npm

### Steps:

#### A. Clone the repo
```bash
git clone https://github.com/masaic-ai-platform/open-responses-chat-playground.git
cd open-responses-chat-playground
```

#### B. Install and Run
```bash
npm install
npm run dev
```

> 📍 UI runs on `http://localhost:3000`

---

## 5. 📊 SigNoz (Observability Platform, if signoz to be used) 

### Option A: Docker (Quick Start)

```bash
git clone https://github.com/SigNoz/signoz.git
cd signoz/deploy/docker
./install.sh
```

> SigNoz UI available at: [http://localhost:3301](http://localhost:3301)  
> Otel Collector endpoint: `http://localhost:4317`

---

## ✅ Validation Checklist

| Component            | URL / Port                  | Status Check                              |
|---------------------|-----------------------------|-------------------------------------------|
| MongoDB             | `localhost:27017`           | Connect using MongoDB Compass or CLI      |
| Qdrant              | `localhost:6333`            | Visit `http://localhost:6333`             |
| Open Responses API  | `http://localhost:8080`     | Open `/actuator/health` endpoint          |
| Platform UI         | `http://localhost:3000`     | UI loads and connects to backend          |
| SigNoz              | `http://localhost:3301`     | Dashboard loads                           |
