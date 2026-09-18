"""Guardian workflow (§12): LangGraph when installed, sequential fallback otherwise.

Flow: understand → signals → RAG/scam-memory → risk → policy → respond.
LangChain is deliberately NOT required; plain LLM calls suffice.
"""
from __future__ import annotations

from typing import Any

from app.ai import llm
from app.ai.memory import CallMemory
from app.detection.social_engineering import analyze_text
from app.intelligence import scam_memory
from app.intelligence.url_intel import analyze_url
from app.detection.signals import extract_urls
from app.risk import policy, scorer
from app.schemas import Signal


def run_guardian_turn(caller_text: str, memory: CallMemory,
                      context_texts: str = "", language: str = "en") -> dict[str, Any]:
    """One guardian turn. Tries LangGraph graph; falls back to sequential."""
    from app.i18n import resolve_language
    lang = resolve_language(language, f"{context_texts}\n{caller_text}")
    try:
        from langgraph.graph import StateGraph  # optional dep
        return _via_langgraph(caller_text, memory, context_texts, lang)
    except Exception:
        return _sequential(caller_text, memory, context_texts, lang)


def _collect_signals(text: str, language: str = "en") -> tuple[list[Signal], list[str]]:
    url_sigs: list[Signal] = []
    for u in extract_urls(text):
        sigs, _ = analyze_url(u)
        url_sigs.extend(sigs)
    signals = analyze_text(text, url_sigs, use_llm=True)
    rag_ctx = scam_memory.retrieve_context(text, language=language)
    return signals, rag_ctx


def _sequential(caller_text: str, memory: CallMemory, context_texts: str = "",
               language: str = "en") -> dict[str, Any]:
    full_text = f"{context_texts}\n{caller_text}".strip()
    signals, rag_ctx = _collect_signals(full_text, language)
    score, level, _ = scorer.score_signals(signals)
    memory.update(caller_text, signals, score)
    decision = policy.decide(level, signals, score, language=language)
    reply = llm.guardian_reply(level, decision.guardian_instruction,
                               caller_text, memory.summary(), language)
    return {"signals": signals, "score": score, "level": level,
            "decision": decision, "reply": reply, "rag_context": rag_ctx}


def _via_langgraph(caller_text: str, memory: CallMemory, context_texts: str = "",
                  language: str = "en"):
    from langgraph.graph import END, StateGraph

    def understand(state: dict) -> dict:
        state["text"] = f"{state.get('context', '')}\n{state['caller']}".strip()
        return state

    def detect(state: dict) -> dict:
        sigs, ctx = _collect_signals(state["text"], state.get("language", "en"))
        state["signals"], state["rag"] = sigs, ctx
        return state

    def decide(state: dict) -> dict:
        lang = state.get("language", "en")
        score, level, _ = scorer.score_signals(state["signals"])
        memory.update(state["caller"], state["signals"], score)
        decision = policy.decide(level, state["signals"], score, language=lang)
        state.update(score=score, level=level, decision=decision,
                     reply=llm.guardian_reply(level, decision.guardian_instruction,
                                              state["caller"], memory.summary(), lang))
        return state

    g = StateGraph(dict)
    g.add_node("understand", understand)
    g.add_node("detect", detect)
    g.add_node("decide", decide)
    g.set_entry_point("understand")
    g.add_edge("understand", "detect")
    g.add_edge("detect", "decide")
    g.add_edge("decide", END)
    out = g.compile().invoke({"caller": caller_text, "context": context_texts, "language": language})
    return {"signals": out["signals"], "score": out["score"], "level": out["level"],
            "decision": out["decision"], "reply": out["reply"], "rag_context": out.get("rag", [])}
