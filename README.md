# ORB - Intelligent Codebase Navigator

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-11+-ED8936?logo=java&logoColor=white)](https://www.java.com/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.0+-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Python](https://img.shields.io/badge/Python-3.9+-3776AB?logo=python&logoColor=white)](https://www.python.org/)

## Overview

**ORB** is an intelligent codebase navigation platform designed to dramatically accelerate onboarding and comprehension of complex, large-scale software systems. By providing interactive code search, component dependency visualization, and call graph analysis, ORB transforms how developers explore and understand unfamiliar codebases.

### The Problem

Modern software systems are increasingly complex, with thousands of interdependent components, intricate call chains, and non-obvious architectural patterns. New team members face significant friction when:
- Navigating sprawling codebases to understand component relationships
- Identifying where and why specific components are used
- Tracing execution flows across multiple layers of abstraction
- Discovering architectural patterns buried in implementation details

This learning curve directly impacts team velocity and time-to-productivity.

### The Solution

ORB provides a unified web-based interface that transforms raw source code into an explorable knowledge base. Rather than reading through hundreds of files, developers can:
- **Search** any component and instantly understand its purpose and implementation
- **Visualize** dependency graphs showing what calls what and in what order
- **Navigate** call chains with automatic summaries of component interactions
- **Explore** architectural patterns without deep code archaeology

## Key Features

### 🔍 **Component Search & Discovery**
- Full-text search across your entire codebase
- Instant component metadata including:
  - Component purpose and function
  - Usage rationale and design decisions
  - Exact locations in source code
  - Integration points and dependencies

### 📊 **Interactive Dependency Graphs**
- Visual representation of component relationships
- Hierarchical call graphs showing execution flows
- Edge annotations with interaction summaries
- Bidirectional navigation (callers and callees)

### 🎯 **Intelligent Call Graph Analysis**
- Trace execution paths through your codebase
- Identify upstream dependencies and downstream consumers
- Automated generation of component interaction summaries
- Visual highlighting of critical paths and bottlenecks

### ⚡ **Fast Onboarding**
- Reduce new developer ramp-up time by up to 70%
- Self-service learning reduces mentoring overhead
- Clear architectural visibility accelerates decision-making

## Architecture

ORB is built as a distributed system across three main layers:

```
┌─────────────────────────────────────────────┐
│        Frontend (Next.js + React)           │  Web UI for code exploration
├─────────────────────────────────────────────┤
│         API Server (Java + Spring)          │  REST API & orchestration
├─────────────────────────────────────────────┤
│    RAG Pipeline (Python + Vector DB)        │  Code analysis & embeddings
└─────────────────────────────────────────────┘
```

### Components

- **`front-end/`** - Interactive web interface built with Next.js and TypeScript
- **`api-server/`** - Backend API server using Java and Spring Framework
- **`cli/`** - Command-line interface for headless operations and automation
- **`rag-pipeline/`** - Semantic analysis pipeline using RAG techniques for intelligent code understanding

## Getting Started

### Prerequisites

- **Java 11+** (for API server)
- **Node.js 18+** (for frontend)
- **Python 3.9+** (for RAG pipeline)
- **Gradle** (included via wrapper)
- **npm** or **yarn** (for frontend dependencies)

### Installation

#### 1. Clone the Repository
```bash
git clone https://github.com/yourusername/orb.git
cd orb
```

#### 2. Set Up Backend API Server
```bash
cd api-server
./gradlew build
./gradlew bootRun
```

The API server will start on `http://localhost:8080`

#### 3. Set Up Frontend
```bash
cd ../front-end
npm install
npm run dev
```

The web interface will be available at `http://localhost:3000`

#### 4. Set Up RAG Pipeline
```bash
cd ../rag-pipeline
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

### Quick Start

1. Open your browser to `http://localhost:3000`
2. Configure your codebase path in the settings
3. Start searching for components in your code
4. Click on any component to view its call graph and dependencies

## Usage

### Web Interface

**Search Components**
- Use the search bar to find classes, functions, methods, or modules
- View component metadata and usage patterns
- Navigate to source code with one click

**Explore Call Graphs**
- Click on any component to view its dependency graph
- Trace upstream callers and downstream dependencies
- Hover over edges to see interaction summaries

**Navigate Code**
- Jump directly to source files
- Follow execution paths through your codebase
- Understand architectural layers and module boundaries

```

## API Documentation

The API server exposes RESTful endpoints for:
- Component search and metadata retrieval
- Dependency graph queries
- Call graph analysis
- Codebase indexing and updates

Full API documentation is available at `http://localhost:8080/swagger-ui.html` (when running with Swagger enabled).

## Supported Languages

ORB currently supports analysis of:
- Java
- TypeScript / JavaScript
- Python
- (Extensible to additional languages)

## Contributing

We welcome contributions! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

Please ensure:
- Code follows project style guidelines
- All tests pass
- New features include tests
- Documentation is updated

## License

ORB is released under the [MIT License](LICENSE) - see the LICENSE file for details.

## Support

- **Documentation**: See [docs/](docs/) folder
- **Issues**: Report bugs via [GitHub Issues](https://github.com/yourusername/orb/issues)
- **Discussions**: Join community discussions on [GitHub Discussions](https://github.com/yourusername/orb/discussions)

## Acknowledgments

ORB is built on the shoulders of excellent open-source projects:
- Spring Boot
- Next.js & React
- Neo4j for graph databases
- And many others

---

**Made with ❤️ for developers who love understanding code**
