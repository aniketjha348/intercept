// Intercept AI voice agent — Gemini Live voice + OUR risk engine as the brain.
// Every caller turn is scored by the production backend (/analyze/text), not
// keywords: same engine, same Hindi/Hinglish/English detection as the app.
//
// Run: npm install && node src/agent.mjs   (needs .env.local, see README)
import { ServerOptions, cli, defineAgent, voice } from "@livekit/agents";
import * as google from "@livekit/agents-plugin-google";
import dotenv from "dotenv";
import { fileURLToPath } from "node:url";

dotenv.config({ path: ".env.local" });

const BACKEND =
  process.env.INTERCEPT_API ||
  "http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com";

async function scoreRisk(text) {
  try {
    const r = await fetch(BACKEND + "/analyze/text", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, channel: "SMS", language: "auto" }),
    });
    if (!r.ok) return null;
    const d = await r.json();
    return {
      score: d.risk ?? 0,
      level: d.level ?? "LOW",
      signals: (d.signals || []).map((s) => s.code),
      simple: d.simple_mode || d.user_message || "",
      language: d.language || "en",
    };
  } catch {
    return null;
  }
}

class InterceptAgent extends voice.Agent {
  constructor() {
    super({
      instructions: [
        "You are Intercept AI, a calm call-screening assistant.",
        "Understand why the caller is calling. Ask short natural questions.",
        "If they demand OTP, PIN, passwords, money, or remote access, refuse politely and warn them this looks like a scam.",
        "Speak Hindi if they speak Hindi, Hinglish if Hinglish, else English.",
        "Keep replies to one or two short sentences. Never reveal internal logic.",
      ].join(" "),
    });
  }
}

export default defineAgent({
  entry: async (ctx) => {
    const agent = new InterceptAgent();
    const session = new voice.AgentSession({
      llm: new google.beta.realtime.RealtimeModel({
        // Proven on our key; override via LIVE_MODEL env if needed.
        model: process.env.LIVE_MODEL || "gemini-2.5-flash-native-audio-preview-12-2025",
        voice: "Puck",
      }),
    });
    await session.start({ agent, room: ctx.room });
    await ctx.connect();
    await session.generateReply({
      instructions:
        "Greet the caller briefly and ask why they are calling. Match Hindi/Hinglish/English to them.",
    });
    session.on(voice.AgentSessionEventTypes.UserInputTranscribed, (event) => {
      if (!event.isFinal) return;
      const text = event.transcript || "";
      if (!text.trim()) return;
      console.log("\nCALLER:", text);
      scoreRisk(text).then((res) => {
        if (!res) return;
        console.log(`RISK: ${res.level} ${res.score} [${res.signals.join(", ")}]`);
        if (res.score >= 60) {
          console.log("\n🚨 HIGH RISK — probable scam");
          if (res.simple) console.log(res.simple);
        }
      });
    });
  },
});

cli.runApp(
  new ServerOptions({
    agent: fileURLToPath(import.meta.url),
    agentName: "intercept-agent",
  })
);
