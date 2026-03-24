# LegalDocInsight

AI-powered legal document analysis platform built using a microservices architecture.

LegalDocInsight allows users to upload legal documents and automatically extract, process, and analyze the content using AI-powered services.

---

# 🚀 Features

- Upload legal documents (PDF, DOCX)
- Automatic **text extraction using Apache Tika**
- Microservices-based document processing
- Secure API routing via **API Gateway**
- AI-powered legal analysis pipeline
- Scalable architecture for large document processing

---

# 🏗 Architecture

```
Frontend (Angular)
        |
        v
   API Gateway
        |
        v
+------------------+
| Document Service |
+------------------+
        |
Extract Text (Apache Tika)
        |
        v
+------------------+
| Analysis Service |
+------------------+
        |
   AI / LLM Engine
        |
        v
   Analysis Results
```

---

# ⚙️ Tech Stack

## Backend
- Java
- Spring Boot
- Spring Cloud Gateway
- WebClient (service-to-service communication)
- Apache Tika (document parsing)

## Frontend
- Angular
- SCSS

## Security
- JWT Authentication

## Database
- H2 (development)
- PostgreSQL (production ready)

## AI Processing
- LLM integration for legal document analysis

---

# 📂 Project Structure

```
LegalDocInsight
│
├── backend
│   ├── api-gateway
│   ├── document-service
│   └── analysis-service
│
└── frontend
    └── Angular Application
```

---

# 🔄 Document Processing Flow

1️⃣ User uploads document from frontend  

2️⃣ Request passes through **API Gateway**

3️⃣ **Document Service**
- stores the document
- extracts text using **Apache Tika**

4️⃣ Extracted text sent to **Analysis Service**

5️⃣ Analysis Service processes document using **AI pipeline**

6️⃣ Results returned to frontend

---

# 📄 Example Workflow

```
Upload Document
      |
      v
Extract Text
      |
      v
Analyze Document
      |
      v
Generate Summary / Insights
```

---

# 🧠 Future Improvements

- LLM-based clause extraction
- Legal risk detection
- Semantic search using vector databases
- Asynchronous processing using Kafka
- Document versioning
- Role-based access control

---

# ▶️ Running the Project

## Start Backend Services

```
cd backend
mvn clean install
```

Run services individually:

```
api-gateway
document-service
analysis-service
```

---

## Start Frontend

```
cd frontend
npm install
ng serve
```

---

# 📌 Learning Goals

This project demonstrates:

- Microservices architecture
- API Gateway pattern
- Document processing pipelines
- AI integration with backend systems
- Service-to-service communication

---

# ⭐ Why This Project Matters

LegalDocInsight showcases how **AI systems can be integrated with backend microservices** to build scalable document intelligence platforms.

---

# 🔗 Future AI Enhancements

- Document summarization
- Clause extraction
- Legal risk detection
- Contract comparison
- AI-powered Q&A over legal documents
