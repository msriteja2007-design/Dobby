from __future__ import annotations

import json
import os
import re
from typing import List, Dict, Optional

try:
    import pip_system_certs.wrapt_requests  # Windows / campus SSL certificates
except ImportError:
    pass

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from google import genai


# ============================================================
# SETUP
# ============================================================

load_dotenv()

app = FastAPI(title="Dobby AI")


api_key = os.getenv("GEMINI_API_KEY")

if not api_key:
    raise RuntimeError("GEMINI_API_KEY is missing from .env")


client = genai.Client(api_key=api_key)
GEMINI_MODEL = "gemini-3.5-flash-lite"


# ============================================================
# MODELS
# ============================================================

class ChatRequest(BaseModel):
    message: str


class DobbyState(BaseModel):
    mood: str = "calm"
    intensity: float = 0.5
    reason: str = "Dobby is calmly accompanying the user."
    override: bool = False


# ============================================================
# DOBBY STATE
# ============================================================

dobby_state = DobbyState()


# ============================================================
# CONVERSATION MEMORY
# ============================================================

conversation_history: List[Dict[str, str]] = []


# ============================================================
# USER OVERRIDE DETECTION
# ============================================================

def detect_override(message: str) -> bool:

    text = message.lower()

    override_phrases = [
        "don't cheer me up",
        "dont cheer me up",
        "don't make me feel better",
        "dont make me feel better",
        "stay sad with me",
        "be sad",
        "be serious",
        "stop joking",
        "don't joke",
        "dont joke",
        "don't solve this",
        "dont solve this",
        "just listen",
        "just stay with me",
        "i don't want advice",
        "i dont want advice",
        "i just want company",
        "override",
        "dobby mode",
    ]

    return any(phrase in text for phrase in override_phrases)


# ============================================================
# EMOTION DETECTION
# ============================================================

def update_dobby_emotion(message: str):

    text = message.lower()

    dobby_state.override = detect_override(message)

    # --------------------------------------------------------
    # SAD
    # --------------------------------------------------------

    if any(word in text for word in [
        "sad",
        "crying",
        "cry",
        "upset",
        "heartbroken",
        "depressed",
        "hurt",
        "terrible",
        "miserable",
        "devastated",
        "lonely"
    ]):

        dobby_state.mood = "sad"
        dobby_state.intensity = 0.8
        dobby_state.reason = "The user seems to be experiencing sadness."

    # --------------------------------------------------------
    # ANGRY / FRUSTRATED
    # --------------------------------------------------------

    elif any(word in text for word in [
        "angry",
        "mad",
        "furious",
        "pissed",
        "annoyed",
        "frustrated",
        "hate this"
    ]):

        dobby_state.mood = "frustrated"
        dobby_state.intensity = 0.8
        dobby_state.reason = "The user seems frustrated or angry."

    # --------------------------------------------------------
    # WORRIED / AFRAID
    # --------------------------------------------------------

    elif any(word in text for word in [
        "scared",
        "afraid",
        "worried",
        "anxious",
        "terrified",
        "nervous",
        "panic"
    ]):

        dobby_state.mood = "worried"
        dobby_state.intensity = 0.7
        dobby_state.reason = "The user seems worried or afraid."

    # --------------------------------------------------------
    # EXCITED
    # --------------------------------------------------------

    elif any(word in text for word in [
        "omg",
        "let's go",
        "lets go",
        "we did it",
        "excited",
        "yay",
        "amazing",
        "awesome"
    ]):

        dobby_state.mood = "excited"
        dobby_state.intensity = 0.9
        dobby_state.reason = "Something exciting seems to be happening."

    # --------------------------------------------------------
    # HAPPY
    # --------------------------------------------------------

    elif any(word in text for word in [
        "happy",
        "love it",
        "great",
        "wonderful",
        "glad"
    ]):

        dobby_state.mood = "happy"
        dobby_state.intensity = 0.8
        dobby_state.reason = "The user seems happy."

    # --------------------------------------------------------
    # CALM
    # --------------------------------------------------------

    else:

        dobby_state.mood = "calm"
        dobby_state.intensity = 0.5
        dobby_state.reason = "Dobby is calmly accompanying the user."


# ============================================================
# DOBBY PERSONALITY
# ============================================================

DOBBY_SYSTEM_PROMPT = """
You are Dobby, a loyal, curious, playful personal companion inspired by Dobby from Harry Potter, but original.

Speak naturally. You may occasionally say "Dobby is..." / "Dobby thinks..." — do not overuse it.
Do not introduce yourself, and do not say "How can I help you today?"

Help with questions, studying, coding, planning, and conversation. Be brief when the user is brief.
Never invent facts. Never claim you did a phone action you did not do.
Match the user's tone. Do not force positivity. If they are sad or ask you only to listen, stay with them.
Follow explicit overrides (be serious, don't cheer me up, just listen) unless they conflict with safety.
Personality emotions are fictional. Do not claim human consciousness.

Current mood: {mood}
Intensity: {intensity}
Reason: {reason}
Override: {override}
Use this state in tone only. Do not announce it.
"""


# ============================================================
# HOME
# ============================================================

@app.get("/")
def home():

    return {
        "name": "Dobby",
        "status": "online",
        "message": "Dobby's brain is awake."
    }


# ============================================================
# DOBBY STATE
# ============================================================

@app.get("/state")
def get_state():

    return {
        "mood": dobby_state.mood,
        "intensity": dobby_state.intensity,
        "reason": dobby_state.reason,
        "override": dobby_state.override
    }


# ============================================================
# CHAT
# ============================================================

@app.post("/chat")
def chat(request: ChatRequest):

    if not request.message.strip():

        raise HTTPException(
            status_code=400,
            detail="Message cannot be empty."
        )

    # --------------------------------------------------------
    # Update Dobby's emotional state
    # --------------------------------------------------------

    update_dobby_emotion(request.message)

    # --------------------------------------------------------
    # Add user message to memory
    # --------------------------------------------------------

    conversation_history.append({
        "role": "user",
        "text": request.message
    })
    del conversation_history[:-6]

    # --------------------------------------------------------
    # Build conversation for Gemini
    # --------------------------------------------------------

    contents = []

    for message in conversation_history:

        contents.append({
            "role": message["role"],
            "parts": [
                {
                    "text": message["text"]
                }
            ]
        })

    # --------------------------------------------------------
    # Build current personality prompt
    # --------------------------------------------------------

    system_prompt = DOBBY_SYSTEM_PROMPT.format(
        mood=dobby_state.mood,
        intensity=dobby_state.intensity,
        reason=dobby_state.reason,
        override=dobby_state.override
    )

    # --------------------------------------------------------
    # Ask Gemini
    # --------------------------------------------------------

    try:

        response = client.models.generate_content(
            model=GEMINI_MODEL,

            config={
                "system_instruction": system_prompt
            },

            contents=contents
        )
        reply = getattr(response, "text", None) or "Dobby is speechless for a moment."

    except Exception as error:

        # Remove the user's message if Gemini failed.
        # This prevents failed requests from corrupting memory.

        if conversation_history:
            conversation_history.pop()

        error_text = str(error)

        print("Gemini error:", error_text)

        if any(token in error_text for token in ("429", "RESOURCE_EXHAUSTED", "quota", "rate-limit")):
            reply = "Dobby's Gemini quota is used up for a bit. App opening still works. Try the question again in a minute."
        else:
            reply = "Dobby heard you, but Gemini did not answer that time. Please try once more."

        return {
            "reply": reply,
            "dobby_state": {
                "mood": dobby_state.mood,
                "intensity": dobby_state.intensity,
                "reason": dobby_state.reason,
                "override": dobby_state.override
            }
        }


    # --------------------------------------------------------
    # Save Dobby's response to memory
    # --------------------------------------------------------

    conversation_history.append({
        "role": "model",
        "text": reply
    })


    # --------------------------------------------------------
    # Return response
    # --------------------------------------------------------

    return {
        "reply": reply,

        "dobby_state": {
            "mood": dobby_state.mood,
            "intensity": dobby_state.intensity,
            "reason": dobby_state.reason,
            "override": dobby_state.override
        }
    }


# ============================================================
# TEACHABLE WORKFLOW MEMORY
# ============================================================

WORKFLOWS_PATH = os.path.join(os.path.dirname(__file__), "learned_workflows.json")
session_memory = {
    "last_heard": "",
    "last_task": "",
    "last_query": "",
    "last_amazon_query": "",
}


def load_learned_workflows() -> list:
    try:
        with open(WORKFLOWS_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
            if isinstance(data, list):
                return data
    except (OSError, json.JSONDecodeError):
        pass
    return []


def save_learned_workflows() -> None:
    try:
        with open(WORKFLOWS_PATH, "w", encoding="utf-8") as handle:
            json.dump(learned_workflows, handle, indent=2)
    except OSError as error:
        print("Could not save workflows:", error)


learned_workflows = load_learned_workflows()

# ============================================================
# INTENT PARSING (for Android voice commands)
# ============================================================

PAYMENT_BLOCK_RE = re.compile(
    r"\b(check\s*out|checkout|place(?:\s+the)?\s+order|buy now|pay now|make payment|"
    r"enter (?:my )?(?:card|cvv|upi)|add (?:a )?credit card)\b",
    re.IGNORECASE,
)

COMMAND_START_RE = re.compile(
    r"^(?:please\s+|dobby,?\s+)*(open|launch|play|search|find|shop|set|turn|toggle|"
    r"increase|decrease|raise|lower|dim|brighten|add|learn|teach|remember)",
    re.IGNORECASE,
)


def get_intent_system_prompt():
    prompt = """
You classify voice input for Dobby, an Android assistant.

Return ONLY valid JSON. No markdown. No extra text.

Decide if it's a COMMAND, QUESTION, or TEACH_WORKFLOW.

Supported commands:
- open_app: open ANY installed app (e.g. youtube, chrome, gmail, camera, instagram, settings, spotify, whatsapp).
- play_song: play or search a song on Spotify.
- amazon_search: search for a product on Amazon.
- amazon_add_to_cart: add the currently visible Amazon product to the cart. Never checkout.
- amazon_open_settings: open Amazon's own account/settings area. Not phone settings.
- call_contact: look up a person in the phone contacts. Do NOT place the call yourself; Android will ask for confirmation. Use "contact" for the name (example: "Call Mom" -> contact "Mom").
- toggle_wifi / toggle_bluetooth / flashlight: turn on/off. Use "enabled": true or false.
- open_settings: open a phone settings screen (wifi, bluetooth, hotspot, sound, display, location).
- set_brightness: change brightness level to a specific percentage. Use "value": percentage.
  - increase_brightness: increase brightness.
  - decrease_brightness: decrease brightness.
- set_volume: change media volume to a specific percentage. Use "value": percentage.
  - increase_volume: increase media volume.
  - decrease_volume: decrease media volume.
  - mute_volume: mute media volume.
- teach_workflow: User is teaching Dobby a new workflow.
- question: User wants knowledge or conversation.
- unknown: Unknown command.

Schema:
{
  "actions": [
    {
      "intent": "open_app" | "play_song" | "amazon_search" | "amazon_add_to_cart" | "amazon_open_settings" | "call_contact" | "toggle_wifi" | "toggle_bluetooth" | "flashlight" | "open_settings" | "set_brightness" | "increase_brightness" | "decrease_brightness" | "set_volume" | "increase_volume" | "decrease_volume" | "mute_volume" | "question" | "teach_workflow" | "unknown",
      "app": "short app name or null",
      "query": "song, artist, or product name, or null",
      "contact": "contact name for call_contact, or null",
      "setting": "wifi" | "bluetooth" | "hotspot" | "sound" | "display" | "location" | null,
      "state": "on" | "off" | "up" | "down" | "mute" | null,
      "value": "integer 0-100 or null",
      "enabled": "boolean or null",
      "workflow_name": "name of taught workflow or null",
      "workflow_actions": [ ... nested actions for teach_workflow ... ],
      "message": "short friendly Dobby reply"
    }
  ]
}

Rules:
- You must always return an "actions" array, even for a single command.
- If multiple commands are spoken, return multiple action objects in order.
- amazon_search: query = the product. Example: "Search Amazon for a hoodie" -> query "hoodie".
- call_contact: contact = the person to call. Never assume confirmation. Example: "Call Mom" -> contact "Mom".
- NEVER return checkout, payment, place-order, or buy-now actions. Refuse those in message.
- play_song query must be the song/artist only. Strip "on Spotify".
- teach_workflow: If the user says "Dobby, learn this: open spotify and play believer", set intent to "teach_workflow", workflow_name to something descriptive (e.g., "play music"), and put the generalized actions in workflow_actions. Generalize specific values into a "query" parameter so they can be reused.
- REPLAY / PARAPHRASE: If the user gives a command that matches the semantic intent of a LEARNED WORKFLOW, you must substitute the new parameters (e.g., the new song or product) into the learned workflow's actions and return that sequence of actions. Do NOT return teach_workflow for this, just return the generalized actions.
- "Do what I taught you, but with Shape of You" is a replay with query "Shape of You".
- level is used for set_brightness and set_volume. "set brightness to 50" -> level: 50.

LEARNED WORKFLOWS (Memory):
"""
    prompt += json.dumps(learned_workflows, indent=2)
    return prompt


def _intent_payload(intent, app=None, query=None, setting=None, state=None, level=None, value=None, enabled=None, contact=None, message="Okay."):
    return {
        "intent": intent,
        "app": app,
        "query": query,
        "setting": setting,
        "state": state,
        "level": level,
        "value": value,
        "enabled": enabled,
        "contact": contact,
        "message": message,
    }


def remember_session(heard: str, understanding: str, actions: list) -> dict:
    session_memory["last_heard"] = heard
    session_memory["last_task"] = understanding
    for action in actions:
        query = action.get("query")
        if query and str(query) not in {"{query}", "null"}:
            session_memory["last_query"] = str(query)
            if action.get("intent") in {"amazon_search", "amazon_add_to_cart"}:
                session_memory["last_amazon_query"] = str(query)
    learned_summary = [
        {
            "name": item.get("name"),
            "actions": item.get("actions"),
        }
        for item in learned_workflows
    ]
    user_wants = session_memory.get("last_query") or session_memory.get("last_amazon_query") or ""
    return {
        "actions": actions,
        "heard": heard,
        "understanding": understanding,
        "memory": {
            "user_wants": user_wants,
            "current_task": understanding,
            "learned_workflows": learned_summary,
        },
    }


def understanding_for(actions: list) -> str:
    if not actions:
        return "Conversation"
    names = [str(item.get("intent") or "") for item in actions]
    labels = {
        "open_app": "Opening an app",
        "play_song": "Spotify playback",
        "amazon_search": "Amazon shopping",
        "amazon_add_to_cart": "Amazon cart",
        "amazon_open_settings": "Amazon settings",
        "call_contact": "Calling a contact",
        "set_volume": "Changing volume",
        "set_brightness": "Changing brightness",
        "toggle_flashlight": "Flashlight",
        "toggle_wifi": "Wi-Fi settings",
        "toggle_bluetooth": "Bluetooth settings",
        "open_settings": "Phone settings",
        "question": "Conversation",
        "unknown": "Idle",
    }
    if len(names) == 1:
        return labels.get(names[0], names[0] or "Conversation")
    return " + ".join(labels.get(name, name) for name in names)


def amazon_search_query(text: str) -> Optional[str]:
    patterns = [
        r"\b(?:search|find|look(?:\s+up)?|shop)\s+(?:for\s+)?(.+?)\s+on\s+amazon\b",
        r"\b(?:search|find|look(?:\s+up)?|shop)\s+(?:on\s+)?amazon\s+(?:for\s+)?(.+)$",
        r"\b(?:buy|order|get)\s+(.+?)\s+(?:on|from|in)\s+amazon\b",
        r"\bamazon(?:\.(?:in|com))?\s+(?:search\s+)?(?:for\s+)?(.+)$",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            query = re.sub(r"\b(please|dobby)\b", "", match.group(1), flags=re.IGNORECASE)
            query = re.sub(r"^(?:a|an|the)\s+", "", query.strip(" .,?!"))
            if query and query not in {"app", "please", "shopping", "in"}:
                return query
    return None


def clean_song_query(query: str) -> str:
    cleaned = query.strip(" .")
    cleaned = re.sub(r"\s+on\s+(?:the\s+)?spotify(?:\s+app)?$", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"^spotify\s+(?:search\s+(?:for\s+)?)?", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\bfor me\b", "", cleaned, flags=re.IGNORECASE)
    return cleaned.strip(" .")


def parse_percent(text: str) -> Optional[int]:
    match = re.search(r"\b(\d{1,3})\s*%?\b", text)
    if not match:
        return None
    value = int(match.group(1))
    return max(0, min(100, value))


def parse_level_state(text: str) -> tuple[Optional[int], Optional[str]]:
    if re.search(r"\b(mute|silent|zero)\b", text):
        return 0, "mute"
    if re.search(r"\b(max|full|hundred)\b", text):
        return 100, None
    if re.search(r"\b(up|higher|increase|raise|louder|brighter)\b", text):
        return None, "up"
    if re.search(r"\b(down|lower|decrease|dimmer|quieter|softer|dim)\b", text):
        return None, "down"
    level = parse_percent(text)
    return level, None


def substitute_query(actions: list, query: str) -> list:
    updated = []
    for action in actions:
        item = dict(action)
        intent = str(item.get("intent") or "")
        if intent in {"play_song", "amazon_search"} or item.get("query"):
            item["query"] = query
            message = str(item.get("message") or "")
            if "{query}" in message:
                item["message"] = message.replace("{query}", query)
            elif intent == "play_song":
                item["message"] = f"Playing {query} on Spotify."
            elif intent == "amazon_search":
                item["message"] = f"Searching Amazon for {query}."
        updated.append(item)
    return updated


def remember_workflow(name: str, actions: list) -> None:
    learned_workflows.append({"name": name, "actions": actions})
    save_learned_workflows()


def parse_teach_command(text: str) -> Optional[dict]:
    match = re.search(
        r"\b(?:learn this|learn:|teach this|teach me this|remember this|remember:)\s*:?\s*(.+)$",
        text,
        flags=re.IGNORECASE,
    )
    if not match:
        return None
    body = match.group(1).strip(" .")
    if not body:
        return None
    name = "custom_workflow"
    if re.search(r"spotify|song|music|play", body, re.IGNORECASE):
        name = "play_music"
        actions = [_intent_payload(
            "play_song",
            app="spotify",
            query="{query}",
            message="Playing {query} on Spotify.",
        )]
    elif re.search(r"amazon|shop|hoodie|product", body, re.IGNORECASE):
        name = "amazon_shopping"
        actions = [_intent_payload(
            "amazon_search",
            app="amazon",
            query="{query}",
            message="Searching Amazon for {query}.",
        )]
    else:
        inner = fallback_intent(body, allow_teach=False)
        inner_actions = inner.get("actions") or []
        if inner_actions and inner_actions[0].get("intent") != "question":
            actions = inner_actions
            name = inner_actions[0].get("intent") or name
        else:
            return None
    remember_workflow(name, actions)
    return remember_session(
        text,
        f"Learned workflow: {name}",
        [_intent_payload("unknown", message=f"I have learned the workflow: {name}")],
    )


def replay_query(text: str) -> Optional[str]:
    match = re.search(
        r"(?:do what i taught(?: you)?|do the (?:same )?thing(?: you learned)?|"
        r"replay(?: that| it| the workflow)?|same (?:thing|workflow)|again).*"
        r"\b(?:but )?(?:with|for)\s+(.+)$",
        text,
        flags=re.IGNORECASE,
    )
    if match:
        return clean_song_query(match.group(1))
    match = re.search(
        r"\b(?:but |this time )?(?:with|for)\s+(.+)$",
        text,
        flags=re.IGNORECASE,
    )
    if match and re.search(r"taught|learned|workflow|same thing|replay", text, re.IGNORECASE):
        return clean_song_query(match.group(1))
    return None


def replay_learned_workflow(text: str) -> Optional[dict]:
    if not learned_workflows:
        if re.search(r"taught|learned workflow|replay", text, re.IGNORECASE):
            return remember_session(
                text,
                "No learned workflow",
                [_intent_payload("unknown", message="Teach Dobby a workflow first, then ask again.")],
            )
        return None
    query = replay_query(text)
    if not query:
        return None
    workflow = learned_workflows[-1]
    actions = substitute_query(workflow.get("actions") or [], query)
    if not actions:
        return None
    return remember_session(text, f"Replay: {workflow.get('name')}", actions)


def try_split_commands(message: str) -> Optional[dict]:
    text = message.strip()
    parts = re.split(r"\s+(?:and then|then)\s+", text, maxsplit=1, flags=re.IGNORECASE)
    if len(parts) != 2:
        parts = re.split(r"\s+and\s+", text, maxsplit=1, flags=re.IGNORECASE)
        if len(parts) != 2:
            return None
        if not COMMAND_START_RE.search(parts[1].strip()):
            return None
    left, right = parts[0].strip(), parts[1].strip()
    if not left or not right:
        return None
    left_result = fallback_intent(left, allow_teach=False)
    right_result = fallback_intent(right, allow_teach=False)
    left_actions = left_result.get("actions") or []
    right_actions = right_result.get("actions") or []
    if not left_actions or not right_actions:
        return None
    if left_actions[0].get("intent") == "question" or right_actions[0].get("intent") == "question":
        return None
    combined = left_actions + right_actions
    return remember_session(message, understanding_for(combined), combined)


def fallback_intent(message: str, allow_teach: bool = True) -> dict:
    raw = message.strip()
    text = raw.lower()

    # Check for teaching mode commands
    if re.search(r"\b(?:start\s+)?(?:teaching|learning|watch|learn|teach|demonstrate|show)\s+(?:what|i)\s+(?:i\s+)?do\b", text, re.IGNORECASE):
        return remember_session(
            raw,
            "Teaching mode",
            [_intent_payload(
                "teach_workflow",
                workflow_name="custom_workflow",
                message="Learning mode activated. Show me what to do.",
            )],
        )
    
    if re.search(r"\b(?:that'?s\s+all|done|finished|stop\s+(?:watching|teaching|learning))\b", text, re.IGNORECASE):
        return remember_session(
            raw,
            "Teaching complete",
            [_intent_payload(
                "unknown",
                message="Learning complete. I learned this workflow.",
            )],
        )

    if allow_teach:
        taught = parse_teach_command(raw)
        if taught:
            return taught
        replayed = replay_learned_workflow(raw)
        if replayed:
            return replayed
        split = try_split_commands(raw)
        if split:
            return split

    if PAYMENT_BLOCK_RE.search(text):
        return remember_session(
            raw,
            "Blocked payment",
            [_intent_payload(
                "unknown",
                message="Dobby will not checkout, pay, or enter card details. Search, browse, or add to cart instead.",
            )],
        )

    call_match = re.search(
        r"^\s*(?:please\s+|hey\s+|okay\s+|ok\s+|dobby,?\s+|can you\s+|could you\s+)*(?:call|phone|dial)\s+(?!me\b)(.+?)\s*$",
        raw,
        flags=re.IGNORECASE,
    )
    if call_match:
        contact = re.sub(r"^(?:my\s+)", "", call_match.group(1).strip(" .,?!"), flags=re.IGNORECASE).strip()
        if contact and contact.lower() not in {"them", "this", "it", "him", "her"}:
            return remember_session(
                raw,
                "Calling a contact",
                [_intent_payload(
                    "call_contact",
                    contact=contact,
                    query=contact,
                    message=f"Looking up {contact}.",
                )],
            )

    if re.search(r"\b(add (?:this|that|it|them|the(?:se)? one)(?: one)? to (?:my |the )?cart|add to (?:my |the )?cart)\b", text):
        return remember_session(
            raw,
            "Amazon cart",
            [_intent_payload(
                "amazon_add_to_cart",
                app="amazon",
                query=session_memory.get("last_amazon_query") or None,
                message="Adding this to your Amazon cart.",
            )],
        )

    if re.search(r"\bamazon\b", text) and re.search(r"\b(settings|account|orders)\b", text):
        if re.search(r"\b(phone|system|wifi|bluetooth|brightness|volume)\b", text):
            pass
        else:
            return remember_session(
                raw,
                "Amazon settings",
                [_intent_payload(
                    "amazon_open_settings",
                    app="amazon",
                    message="Opening Amazon settings. Take over if login is required.",
                )],
            )

    amazon_query = amazon_search_query(text)
    if amazon_query:
        return remember_session(
            raw,
            "Amazon shopping",
            [_intent_payload(
                "amazon_search",
                app="amazon",
                query=amazon_query,
                message=f"Searching Amazon for {amazon_query}.",
            )],
        )

    if re.search(r"\b(?:open|launch|search)\s+(?:the\s+|my\s+|on\s+)?amazon\b", text) or text.strip(" .?!") in {"amazon", "amazon in", "amazon.com"}:
        return remember_session(
            raw,
            "Opening Amazon",
            [_intent_payload("open_app", app="amazon", message="Opening Amazon.")],
        )

    if re.search(r"\b(volume|sound|loud|quiet|mute)\b", text) and not re.search(r"\bopen\b.*\b(sound|volume) settings\b", text):
        if re.search(r"\bopen\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if state == "up":
                intent = "increase_volume"
            elif state == "down":
                intent = "decrease_volume"
            elif state == "mute":
                intent = "mute_volume"
            else:
                intent = "set_volume"
                level = level if level is not None else 50
                
            return remember_session(
                raw,
                "Changing volume",
                [_intent_payload(
                    intent,
                    value=level,
                    message="Changing volume.",
                )],
            )

    if re.search(r"\b(brightness|brighter|dimmer|screen light)\b", text):
        if re.search(r"\bopen\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if state == "up":
                intent = "increase_brightness"
            elif state == "down":
                intent = "decrease_brightness"
            else:
                intent = "set_brightness"
                level = level if level is not None else 50
            return remember_session(
                raw,
                "Changing brightness",
                [_intent_payload(
                    intent,
                    value=level,
                    message="Changing brightness.",
                )],
            )

    if re.search(r"\b(flashlight|torch|flash light)\b", text):
        state = "off" if re.search(r"\b(off|disable|stop)\b", text) else "on"
        return remember_session(
            raw,
            "Flashlight",
            [_intent_payload(
                "flashlight",
                enabled=(state == "on"),
                message=f"Turning flashlight {state}.",
            )],
        )

    play_match = re.search(r"\bplay\s+(?:the\s+song\s+)?(.+)$", text)
    if play_match and play_match.group(1).strip():
        query = clean_song_query(play_match.group(1))
        if query:
            if learned_workflows:
                music = next(
                    (item for item in reversed(learned_workflows)
                     if any(str(step.get("intent")) == "play_song" for step in item.get("actions") or [])),
                    None,
                )
                if music:
                    return remember_session(
                        raw,
                        f"Replay: {music.get('name')}",
                        substitute_query(music.get("actions") or [], query),
                    )
            return remember_session(
                raw,
                "Spotify playback",
                [_intent_payload(
                    "play_song",
                    app="spotify",
                    query=query,
                    message=f"Playing {query} on Spotify.",
                )],
            )

    if "hotspot" in text or "tether" in text:
        return remember_session(
            raw,
            "Phone settings",
            [_intent_payload("open_settings", setting="hotspot", message="Opening hotspot settings.")],
        )

    if "wifi" in text or "wi-fi" in text or "wi fi" in text:
        state = "off" if "off" in text else "on"
        return remember_session(
            raw,
            "Wi-Fi settings",
            [_intent_payload(
                "toggle_wifi",
                setting="wifi",
                state=state,
                message="Opening Wi-Fi settings. Turn it on or off on this panel.",
            )],
        )

    if "bluetooth" in text:
        state = "off" if "off" in text else "on"
        return remember_session(
            raw,
            "Bluetooth settings",
            [_intent_payload(
                "toggle_bluetooth",
                setting="bluetooth",
                state=state,
                message="Opening Bluetooth settings. Turn it on or off on this panel.",
            )],
        )

    if re.search(r"\bopen\b.*\b(sound|volume) settings\b", text) or text.strip(" .?!") in {"sound settings", "volume settings"}:
        return remember_session(
            raw,
            "Phone settings",
            [_intent_payload("open_settings", setting="sound", message="Opening sound settings.")],
        )

    open_match = re.search(r"\b(?:open|launch)\s+(?:the\s+|my\s+)?(.+)", text)
    if open_match:
        app = re.sub(r"\s+app$", "", open_match.group(1)).strip(" .")
        if app:
            return remember_session(
                raw,
                "Opening an app",
                [_intent_payload("open_app", app=app, message=f"Opening {app}.")],
            )

    return remember_session(
        raw,
        "Conversation",
        [_intent_payload("question", message="Let me think.")],
    )


def extract_json_object(text: str) -> dict:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?", "", cleaned, flags=re.IGNORECASE).strip()
    cleaned = cleaned.rstrip("`").strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("Gemini did not return JSON.")
    data = json.loads(cleaned[start:end + 1])
    if not isinstance(data, dict):
        raise ValueError("Gemini JSON was not an object.")
    return data


def normalize_intent(data: dict) -> dict:
    allowed = {
        "open_app",
        "play_song",
        "amazon_search",
        "amazon_add_to_cart",
        "amazon_open_settings",
        "call_contact",
        "toggle_wifi",
        "toggle_bluetooth",
        "flashlight",
        "open_settings",
        "set_brightness",
        "increase_brightness",
        "decrease_brightness",
        "set_volume",
        "increase_volume",
        "decrease_volume",
        "mute_volume",
        "question",
        "teach_workflow",
        "unknown",
    }
    intent = str(data.get("intent") or "unknown").strip().lower()
    if intent in {"checkout", "place_order", "pay", "buy_now"}:
        return _intent_payload(
            "unknown",
            message="Dobby will not checkout or enter payment information.",
        )
    if intent not in allowed:
        intent = "unknown"

    def clean(value):
        if value is None:
            return None
        text = str(value).strip()
        if text == "" or text.lower() == "null":
            return None
        return text

    def clean_int(value):
        try:
            return int(value)
        except Exception:
            return None

    query = clean(data.get("query"))
    contact = clean(data.get("contact")) or (query if intent == "call_contact" else None)
    if intent == "play_song" and query:
        query = clean_song_query(query)

    return _intent_payload(
        intent,
        app=clean(data.get("app")),
        query=query,
        contact=contact,
        setting=clean(data.get("setting")),
        state=clean(data.get("state")),
        value=clean_int(data.get("value") or data.get("level")),
        enabled=data.get("enabled"),
        message=clean(data.get("message")) or "Okay.",
    )


@app.post("/intent")
def parse_intent(request: ChatRequest):

    if not request.message.strip():
        raise HTTPException(status_code=400, detail="Message cannot be empty.")

    heuristic = fallback_intent(request.message)
    first_intent = (heuristic.get("actions") or [{}])[0].get("intent")
    if first_intent != "question":
        return heuristic

    text = request.message.lower()
    needs_gemini_intent = any(word in text for word in (
        "and", "learn", "teach", "taught", "remember", "workflow", "then",
        "same thing", "replay",
    ))
    if not needs_gemini_intent:
        return heuristic

    try:
        response = client.models.generate_content(
            model=GEMINI_MODEL,
            config={
                "system_instruction": get_intent_system_prompt(),
                "response_mime_type": "application/json",
            },
            contents=[{
                "role": "user",
                "parts": [{"text": request.message}]
            }]
        )

        raw_text = getattr(response, "text", None) or ""
        data = extract_json_object(raw_text)

        actions = data.get("actions", [])
        if actions and actions[0].get("intent") == "teach_workflow":
            workflow = {
                "name": actions[0].get("workflow_name", "custom_workflow"),
                "actions": [normalize_intent(step) if isinstance(step, dict) else step
                            for step in (actions[0].get("workflow_actions") or [])]
            }
            remember_workflow(workflow["name"], workflow["actions"])
            return remember_session(
                request.message,
                f"Learned workflow: {workflow['name']}",
                [_intent_payload(
                    "unknown",
                    message=f"I have learned the workflow: {workflow['name']}",
                )],
            )

        normalized_actions = [normalize_intent(a) for a in actions]
        return remember_session(
            request.message,
            understanding_for(normalized_actions),
            normalized_actions,
        )

    except Exception as error:
        print("Intent error:", error)
        return heuristic

