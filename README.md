YES 😭 — \*\*those sections are important\*\*. The README I just gave you already has them, but if you want the \*\*full proper hackathon README\*\*, you should include \*\*Tech Stack + Backend Setup + Android Setup + Project Structure\*\*.



You \*\*do not need to write anything else tonight\*\*. Use this version instead — \*\*paste the whole thing\*\*:



````markdown

\# 🧦 Dobby — Teachable Voice Automation



> An AI-powered Android assistant that learns workflows from user demonstrations and reuses them through natural voice commands.



\## 🪄 Overview



Dobby is a voice-driven Android assistant designed to make mobile automation more natural and accessible.



Instead of requiring users to manually define every automation, Dobby can observe a workflow demonstrated by the user, understand the semantic information available on the screen, and create a reusable workflow.



Users can later invoke learned workflows using natural voice commands.



\### Core Idea



\*\*Show Dobby once → Dobby learns → Ask naturally → Dobby reuses\*\*



\---



\## 🎯 Problem



Traditional mobile automation often depends on:



\- Fixed commands

\- Hard-coded workflows

\- Screen coordinates

\- Manually configured actions



These approaches can become fragile when wording, values, or UI layouts change.



\---



\## 💡 Solution



Dobby combines:



\- 🎤 Voice interaction

\- 🧠 Generative AI

\- 👁️ Semantic UI observation

\- ⚙️ Workflow automation

\- 🎓 Teach \& Demonstrate

\- 🔄 Reusable learned workflows



Dobby focuses on understanding the meaning of UI elements and user intent rather than relying only on fixed screen coordinates.



\---



\## ✨ Key Features



\- Natural voice commands

\- Speech-to-text interaction

\- Generative AI-based intent understanding

\- Text-to-speech responses

\- Floating Dobby assistant

\- Android Accessibility-based UI observation

\- Teach \& Demonstrate learning mode

\- Semantic workflow representation

\- Workflow reuse

\- Multi-step interaction

\- Cloud-connected AI backend

\- Visual execution states



\### Execution States



\*\*LISTENING → THINKING → ACTING → SPEAKING → DONE\*\*



Additional state: \*\*ERROR\*\*



\---



\## 🎓 Teach \& Demonstrate



Teach \& Demonstrate is Dobby's main differentiating capability.



The user can activate Learning Mode and demonstrate a task while Dobby observes the available semantic information from the Android interface.



\### Learning Flow



1\. User activates Teaching Mode.

2\. Dobby observes the demonstrated interaction.

3\. Android Accessibility Services provide available UI information.

4\. Dobby converts the observed interaction into a structured workflow.

5\. The workflow is stored locally.

6\. The learned workflow can later be invoked using a natural voice command.



\### Example



\*\*User:\*\* "Dobby, start teaching."



Dobby enters Learning Mode and observes the user's interaction.



Later:



\*\*User:\*\* "Dobby, do that task again."



Dobby attempts to identify and execute the learned workflow.



\---



\## 🏗️ System Architecture



```text

&#x20;                   USER

&#x20;                    │

&#x20;                    ▼

&#x20;             Voice Command

&#x20;                    │

&#x20;                    ▼

&#x20;             Speech-to-Text

&#x20;                    │

&#x20;                    ▼

&#x20;             Android Dobby App

&#x20;                    │

&#x20;                    ▼

&#x20;             FastAPI Backend

&#x20;                    │

&#x20;                    ▼

&#x20;                Gemini AI

&#x20;                    │

&#x20;                    ▼

&#x20;           Intent Understanding

&#x20;                    │

&#x20;                    ▼

&#x20;             Workflow Manager

&#x20;                    │

&#x20;                    ▼

&#x20;      Android Accessibility Service

&#x20;                    │

&#x20;                    ▼

&#x20;            Semantic UI Actions

&#x20;                    │

&#x20;                    ▼

&#x20;            Dobby Voice Response

````



\---



\# 🛠️ Technology Stack



\## Android



\* Kotlin

\* Android Studio

\* Android Accessibility Services

\* Speech-to-Text

\* Text-to-Speech

\* Floating Overlay UI

\* Gradle

\* Android SDK



\## AI \& Backend



\* Python

\* FastAPI

\* Google Gemini API

\* REST API

\* Pydantic

\* Uvicorn



\## Automation



\* Android Accessibility Services

\* Semantic UI observation

\* Structured workflow representation

\* Local workflow storage

\* Workflow matching and execution



\## Development \& Deployment



\* Git

\* GitHub

\* ADB

\* Render



\---



\# 📁 Project Structure



```text

Dobby/

├── android/        # Android application

├── backend/        # FastAPI + Gemini backend

├── docs/           # Documentation

├── README.md       # Project documentation

└── render.yaml     # Backend deployment configuration

```



\---



\# 🚀 Setup \& Installation



\## Prerequisites



\* Android Studio

\* Android SDK

\* Android device

\* USB debugging enabled

\* JDK

\* Python 3.x

\* Git



\---



\## 🧠 Backend Setup



Clone the repository:



```bash

git clone https://github.com/msriteja2007-design/Dobby.git

cd Dobby

```



Install backend dependencies:



```bash

pip install -r backend/requirements.txt

```



Configure the Gemini API key as an environment variable:



```text

GEMINI\_API\_KEY=your\_api\_key\_here

```



Run the backend locally:



```bash

uvicorn backend.main:app --host 0.0.0.0 --port 8000

```



The backend provides the AI processing layer used by the Android application.



\### Cloud Backend



Dobby can also connect to its deployed FastAPI backend through Render.



The Gemini API key is stored securely in the backend environment and is not embedded in the Android application.



\---



\# 📱 Android Setup



1\. Open the `android` folder in Android Studio.

2\. Allow Android Studio to sync the Gradle project.

3\. Connect an Android device using USB debugging.

4\. Build the application.

5\. Install the generated APK.

6\. Launch Dobby.

7\. Grant microphone permission.

8\. Allow the required overlay permission.

9\. Enable Dobby's Accessibility Service.

10\. Start interacting with Dobby through voice commands.



\### Building the APK



From the `android` directory:



```bash

./gradlew assembleDebug

```



The generated debug APK can then be installed on a connected Android device.



\---



\# 🔐 Security \& Permissions



Dobby uses Android permissions required for:



\* Microphone access

\* Floating overlay interaction

\* Accessibility-based UI observation

\* Network communication

\* System settings interaction where required



API keys and secrets should be stored using environment variables and must not be committed to the repository.



For sensitive actions such as payments, OTPs, passwords, or login credentials, Dobby should pause instead of automatically entering sensitive information.



\---



\# ⚠️ Limitations



\* Workflow matching depends on the semantic information exposed by an Android application.

\* Some applications may expose limited Accessibility information.

\* Complex UI changes may require additional workflow reasoning.

\* AI functionality depends on network connectivity and backend availability.

\* The current workflow system is a prototype and may require further refinement for highly complex tasks.



\---



\# 🔮 Future Scope



\* Advanced workflow generalization

\* Improved contextual understanding

\* More complex multi-app automation

\* Stronger on-device intelligence

\* More natural conversational interaction

\* Secure synchronization of learned workflows

\* Broader application support

\* Personalized long-term assistance



\---



\# 🏆 Hackathon



\*\*Samsung PRISM Generative AI Hackathon — 3rd Edition 2026–27\*\*



\*\*Theme:\*\* Teachable Voice Automation



Dobby explores how generative AI and semantic UI understanding can transform mobile automation from predefined commands into demonstration-based learning.



\---



\# 📌 Project Status



Dobby is an active prototype developed for the Samsung PRISM hackathon.



The current implementation includes:



\* Voice interaction

\* AI-powered intent handling

\* Android Accessibility-based observation

\* Teach \& Demonstrate learning mode

\* Workflow representation and storage

\* Reusable automation

\* Floating assistant interaction

\* Cloud-connected AI backend



```





