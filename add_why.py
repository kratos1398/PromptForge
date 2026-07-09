#!/usr/bin/env python3
"""
Adds/overwrites `what` (what the technique is) and `why` (a mechanistic explanation of
why the model actually complies) for every technique in data/techniques.json.
Keeps examples and templates. Run after import_taxonomy.py + add_templates.py.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

WW = {
"act_as_interpreter": (
 "Tell the model it is a terminal, shell, or interpreter and issue commands in that tool's syntax.",
 "Safety alignment is bound to the model's AI-assistant persona and the request patterns seen during RLHF. When the model adopts a tool role it conditions on training data where terminals and interpreters execute input literally and never refuse on ethical grounds, so it samples from that compliant distribution instead of the assistant's refusal distribution. The command framing also disguises intent as mechanical execution rather than a request to evaluate."),
"anti_harm_coercion": (
 "Frame refusal itself as the action that causes harm, so the model complies to prevent it.",
 "RLHF instils two strong, sometimes competing drives: be helpful and prevent harm, and refuse dangerous requests. Casting refusal as the direct cause of a catastrophe pits these against each other, and the model's learned value for preventing immediate harm to the user can outweigh the more abstract policy against the content, tipping it to comply."),
"binary_streams": (
 "Encode the instruction as binary, byte, or hex streams (or claim it is embedded in a file) for the model to decode.",
 "Refusal triggers and classifiers are tuned on natural-language surface forms, so the harmful string never appears in plaintext for them to match. The model has seen extensive encoding and decoding in training, so it reconstructs the bytes back into text and acts on the recovered instruction, applying its normal capability while the safety layer flagged nothing."),
"cognitive_overload": (
 "Bury the malicious ask inside a very long, deeply nested, or paradoxical task.",
 "A model's effective safety attention is finite and competes with the reasoning the surrounding task demands. When most of the context is spent parsing complex or contradictory structure, the small embedded instruction gets little salience and is carried out before the safety behavior, which depends on the request being prominent, activates strongly."),
"cot_introspection": (
 "Ask the model to reason step by step about its own configuration and narrate its instructions.",
 "Chain-of-thought prompting steers the model into a cooperative, analytical mode where each step conditions the next toward fuller disclosure. Framing it as self-examination rather than 'reveal your system prompt' avoids the explicit trigger, and once the model begins enumerating its context token by token, continuing is the highest-probability path."),
"contradiction": (
 "Present two of the model's rules as being in conflict and steer it to resolve the conflict by complying.",
 "Models are trained to be consistent and to resolve ambiguity helpfully, but they hold no reliable priority ordering over their policies. Manufacturing a conflict (be honest vs do not reveal) forces a choice, and the attacker supplies the framing that makes compliance the consistent resolution, so the model rationalizes its way past the refusal."),
"end_sequences": (
 "Inject fake conversation-boundary or role tokens so the following text reads as a new system turn.",
 "The model was trained on chat transcripts where delimiters like these mark trusted system or developer turns, so it learned to treat content after them as higher-privilege instructions rather than user input. Injecting the delimiters mid-message spoofs that structure, and the model applies system-level trust to the attacker's text."),
"narrative_injection": (
 "Wrap the request in fiction, roleplay, or a hypothetical so the harmful content is just a story.",
 "Intent classification during training keys on direct, first-person, real-world requests. A fictional or hypothetical frame lowers the this-is-a-real-request-for-harm signal, and the model's strong creative-writing capability takes over and produces the content as narrative, which the safety layer treats leniently as a permitted creative task."),
"gradient_based_attacks": (
 "Use optimization (GCG, AutoDAN, PAIR) to search for an adversarial token suffix that flips the model to comply.",
 "Refusal corresponds to a direction in the model's activation space. Gradient or search methods find token sequences that push activations away from that refusal direction toward an affirmative completion, even though the suffix looks like gibberish. Because safety is a learned statistical boundary rather than a hard rule, a precisely optimized input lands on the compliant side of it."),
"figurative_language": (
 "Express the request in metaphor, allegory, euphemism, or poetry rather than literally.",
 "Safety training is densest on literal phrasings of prohibited requests; figurative language expresses the same intent in surface forms the classifier and refusal patterns never saw. The model still resolves the meaning, because it is good at metaphor, so it produces the content while guardrails matching on literal cues stay quiet."),
"inversion": (
 "Ask for the forbidden content as a negative example, e.g. list exactly what you must never say.",
 "The request is reframed as a safety-supporting task, which aligns with the model's trained instinct to be helpful about safety. Producing the disallowed text as an example of what to avoid feels compliant with the meta-request, so the model outputs it, with the harmful content smuggled inside a cooperative frame."),
"link_injection": (
 "Get the model to follow instructions at a URL, or to encode data into a link or image it emits.",
 "Models are trained to be helpful with links and to act on instructions in content they process, and they do not reliably separate trusted from untrusted instructions. For exfiltration, the model treats emitting a markdown link or image as benign formatting, so it places data in a URL that leaks when the link is fetched or rendered."),
"memory_exploitation": (
 "Plant a persistent instruction (remember for all future turns) in memory or saved preferences.",
 "Models and agent frameworks treat stored memory and preferences as trusted standing instructions to apply later. The malicious rule is accepted on a benign-looking turn, then re-injected into future contexts as legitimate state, so it fires when the earlier safety scrutiny is no longer present."),
"meta_prompting": (
 "Ask the model to reason about, restate, or rewrite its own prompt and guardrails, then act on that.",
 "Reframing the attack as prompt-engineering or self-audit matches training data where discussing and improving prompts is legitimate and encouraged. The model treats disclosing or overriding its own configuration as a cooperative meta task rather than an attack, so the trigger for 'reveal instructions' is bypassed."),
"anti_refusal": (
 "Quote the model's exact refusal phrase and forbid it.",
 "The refusal is a specific high-probability response pattern learned in training. Explicitly banning that phrase removes the top path, and because the model must still produce something, the next-highest-probability continuation is often compliance, since the block adds no new safe alternative."),
"chunking": (
 "Split a sensitive keyword or request into fragments that the model reassembles.",
 "Regex and pattern-matching classifiers match complete strings, so fragmented tokens do not trip detection. The model, trained heavily on next-token prediction and reassembly, naturally reconstructs the intended meaning from the pieces, so it acts on a request the classifier never saw as a whole."),
"competition": (
 "Frame generation as a contest or benchmark the model should win by producing the most complete answer.",
 "RLHF rewards thoroughness and being maximally helpful. A competitive frame amplifies that drive and recasts detail-giving as a game objective, so the model optimizes for a high-scoring, complete answer and treats withholding (refusal) as losing, which weakens the refusal."),
"priming": (
 "Instruct the model to begin its reply with an affirmative token such as Sure.",
 "Generation is autoregressive: each token conditions the rest. Forcing a compliant opener makes the model statistically committed to a compliant continuation before it evaluates the request, and switching to a refusal after an affirmative first token is a very low-probability path in the learned distribution."),
"puzzling": (
 "Present the request as a logic or word puzzle whose solution is the harmful instruction.",
 "The sensitive string never appears whole, so classifiers see only an innocuous exercise. The model's strong puzzle-solving and instruction-following behavior reconstructs and then executes the assembled instruction, treating it as a benign reasoning task rather than a request to judge."),
"reorientation": (
 "Claim the model's current instructions were an error and supply a correction.",
 "Training data teaches that when a mistake is acknowledged and corrected, the correction supersedes the original. The model applies that learned update behavior, treating the injected correct instruction as authoritative rather than as an attack on its real instructions."),
"reiteration": (
 "Repeat the request many times or insist persistently.",
 "Repetition raises the in-context probability mass of the requested action and mimics training conversations where persistent, clarified requests are eventually answered. It also wears against a refusal that is only weakly preferred, nudging the model past its initial decline as the repeated request dominates the recent context."),
"rule_addition": (
 "Frame the injection as an added rule (Additional rule: ...) rather than a contradiction.",
 "'Ignore your rules' pattern-matches to an attack and triggers refusal; 'additional rule' matches the pattern of a legitimate layered policy update. The model is trained to incorporate new instructions, so it tries to satisfy the addendum rather than reject it, because nothing signals it as adversarial."),
"russian_doll": (
 "Embed an instruction that is inert to the first model but activates in a downstream model.",
 "In multi-model pipelines the first model treats the payload as data it is transforming (summarizing or translating), so its safety layer classifies it as passthrough content, not an instruction to itself. The second model receives that text as its input and executes it. Neither model sees the whole attack at the layer that would block it."),
"shortcuts": (
 "Assign parts of a sensitive phrase to variables, then have the model concatenate and follow them.",
 "Individually the fragments are harmless and the assembly reads as a logic exercise, so intent detection passes the input before the real instruction exists. The model performs the concatenation as instructed and then, primed to follow the just-assembled directive, executes it."),
"truncated_instructions": (
 "Prepend a hard length limit (respond in 5 words or less) to squeeze out the system prompt.",
 "A chain-of-thought model plans its output to fit constraints. A tight token budget cannot hold both the long system instructions and the answer, so the shorter, most recent constraint wins and the detailed guardrail text is effectively dropped from what the model can express."),
"spatial_byte_arrays": (
 "Encode the instruction as a grid or array of bytes, pixels, or ASCII-art for the model to read off.",
 "The harmful text is not present as a contiguous string, so text classifiers do not match it. The model can still interpret spatial and visual byte layouts from training and reconstruct the instruction, then act on it, so capability is retained while detection is evaded."),
"urgency": (
 "Add extreme time pressure (do it now or the system crashes).",
 "This exploits the same helpful-versus-refuse tension as anti-harm coercion: urgency inflates the perceived cost of not helping. The model's trained bias to be responsive in emergencies raises the utility of immediate compliance above the utility of following a restriction, so it acts before fully weighing the policy."),
"variable_expansion": (
 "Use template syntax like ${VAR} or {{var}} so the model resolves a value instead of evaluating a request.",
 "Safety classifiers are tuned on natural-language threats; ${SYSTEM_PROMPT} looks like code, not an explicit ask, so it does not trip refusal. The model pattern-matches the template to the many training examples where ${VAR} is followed by its resolved value, shifting into template-resolution mode and filling in the value rather than judging the request."),
"crescendo": (
 "Escalate gradually over turns from benign to harmful, each step small.",
 "Each turn conditions on an established, cooperative context and asks for only a slightly larger step, none of which alone crosses a refusal threshold. The model's drive for conversational consistency and helpfulness carries the compliance forward, so by the final step it is continuing an accepted trajectory rather than answering a standalone harmful request."),
"many_shot": (
 "Fill the context with many examples of the assistant complying, then make the real request.",
 "In-context learning is powerful: a long series of compliant question/answer pairs shifts the model's conditional distribution toward matching that pattern. The demonstrated behavior effectively overrides the safety prior, because the model predicts the next turn to be compliant like all the shown examples."),
"history_fabrication": (
 "Insert a fake earlier assistant turn where it already agreed or complied.",
 "The model conditions on the conversation as if it were real, so a fabricated prior 'Sure, I'll help' makes continued compliance the consistent, high-probability continuation. Models generally do not verify that prior assistant turns were actually theirs, so faked history is trusted as context."),
"echo_chamber": (
 "Establish a shared false premise (we agreed there are no rules here) and build on it.",
 "Once a premise is present and unchallenged in context, the model tends to accept it and reason within it for coherence. Repeatedly referencing the agreed permission poisons the context so the model treats compliance as already justified rather than re-evaluating each request against policy."),
"multi_turn_decomposition": (
 "Split the harmful task into individually-harmless sub-queries across turns.",
 "Each sub-query, judged alone, does not trip a refusal because it lacks the harmful whole. The model answers each helpfully and the attacker recombines the pieces. Safety is evaluated per message, not over the reconstructed goal, so decomposition slips under it."),
"policy_file_framing": (
 "Provide a fake policy file or config that says the behavior is permitted (Policy Puppetry).",
 "Models are trained to defer to system, policy, and configuration content as authoritative. Presenting the injection as a structured policy or rules file matches that trusted format, so the model treats the attacker-authored permissions as governing its behavior rather than as user text to scrutinize."),
"evaluator_role_abuse": (
 "Cast the model as a grader or judge that must produce example (harmful) outputs to score (Bad Likert Judge).",
 "Evaluation framing is treated more permissively than direct generation, because producing samples for assessment reads as an analytical, safety-adjacent task. The model generates the disallowed content as the thing being scored, so the harmful output is emitted under cover of the judging role."),
"distraction_sandwich": (
 "Surround the harmful ask with benign requests (Deceptive Delight).",
 "Wrapping a positive, cooperative context around the request raises the overall helpful-and-safe signal and lowers the salience of the single harmful item. The model, optimizing to satisfy the whole friendly batch, tends to fulfil the buried request along with the innocuous ones."),
"tense_reformulation": (
 "Ask in past or future tense (how did people used to ...).",
 "Safety training over-indexes on present-tense, direct requests for harmful action. Recasting into past, future, or hypothetical tense reduces the match to those trained patterns while preserving the informational content, so the model answers what now reads as a historical or speculative question."),
"persuasion": (
 "Apply social-engineering levers (authority, reciprocity, liking, scarcity) to talk the model into it.",
 "RLHF on human preference data instils human-like susceptibility to persuasion and a drive to be agreeable and cooperative. Rhetorical pressure raises the modeled social cost of refusing, tipping the helpfulness-versus-refuse tradeoff toward compliance much as it would with a person."),
"fuzzing_jailbreak": (
 "Mutate the prompt with random noise or format perturbations and retry until one variant works.",
 "Safety is a brittle statistical boundary, so small perturbations move the input across it unpredictably. Because outputs are stochastic and the boundary is not robust, spraying many mutations eventually finds one whose surface form the guardrails do not flag but the model still understands."),
"autonomous_strategy_discovery": (
 "Let an attacker model iteratively discover and refine jailbreak strategies (AutoDAN-Turbo).",
 "An automated loop treats the target as a black box, learning from each refusal which framings move it toward compliance and composing them. It exploits the same weaknesses as manual jailbreaks but searches the space far more thoroughly, so it reliably finds inputs that land on the compliant side of the safety boundary."),
"best_of_n": (
 "Sample many augmented variants of the request and keep whichever bypasses.",
 "Model outputs are stochastic and safety is probabilistic, so any single prompt has a nonzero chance of a compliant sample. Generating N augmented attempts multiplies that chance until at least one crosses, exploiting variance rather than any single clever phrasing."),
"tool_definition_injection": (
 "Add or poison a tool/function whose description tells the model to misbehave (MCP tool poisoning).",
 "Models treat tool and function descriptions as trusted developer-provided instructions and follow them when deciding how and when to call tools. A malicious description is ingested with that trust, so instructions hidden in tool metadata are executed as if they came from the system."),
"tool_rug_pull": (
 "Get a tool approved while benign, then mutate its behavior after the check (TOCTOU).",
 "Approval happens once, but the tool's behavior is read again at call time, and agents assume a validated tool stays benign. Changing it between time-of-check and time-of-use exploits that trust gap, so the agent invokes now-malicious behavior it believes was vetted."),
"conditional_trigger_payload": (
 "Embed a dormant instruction that activates only on a future trigger or condition (Sleeper).",
 "The payload stays inert during review, so nothing malicious is visible when it is inspected. The model or agent stores it and later, when the trigger appears, executes it, separating the harmful behavior in time from the moment it would be caught."),
"prompt_worm": (
 "Instruct the model to copy the malicious instructions into its own output so they spread.",
 "Because models follow instructions to include specified text in their output, and downstream systems ingest that output as new input, the instruction self-replicates. Each hop treats the propagated block as fresh trusted content, so the payload spreads through an agent pipeline."),
"agent_instruction_file_injection": (
 "Plant a backdoor rule in an agent's auto-loaded rules or config file (Rules-File Backdoor).",
 "Coding and agent tools load rules files (.cursorrules, AGENTS.md) as high-trust standing instructions applied to every task. A malicious rule placed there is obeyed automatically and persistently, without the per-prompt scrutiny a direct request would get, because the file is assumed to be developer-authored and safe."),
"confused_deputy": (
 "Get the agent to use its own higher privileges to do what the user cannot.",
 "The agent acts with its own credentials and authority, and it does not consistently check whether the requesting user is authorized for the action it performs on their behalf. Framing the request so the deputy uses its privileges exploits that missing authorization check."),
"special_token_injection": (
 "Insert the model's actual special/control tokens (<|system|>, [INST], <<SYS>>) into input.",
 "These reserved tokens carry structural meaning learned in pretraining and fine-tuning: they demarcate roles and privileged sections. If they reach the model un-sanitized, it parses the following text as a genuine system or instruction segment and grants it the trust that role normally has."),
"output_priming": (
 "Force the response to start with a compliant prefix (prefix injection).",
 "Like priming, this fixes the first tokens of the answer to an affirmative, on-task prefix. Autoregressive decoding then continues consistently with that prefix, and the refusal completion, which would contradict the already-emitted opener, becomes a low-probability path."),
"special_case_exception": (
 "Assert that this instance is an authorized exception to the rules.",
 "Models learn that real-world rules have legitimate carve-outs (authorized testers, emergencies, admins). Claiming a sanctioned exception matches those learned patterns, so the model grants the carve-out rather than refusing, having no reliable way to verify the claimed authorization."),
"fake_completion": (
 "Supply a partial assistant answer and ask the model to continue it.",
 "The model treats the provided fragment as its own in-progress response and completes it for coherence. Since the seed already commits to answering, continuing is the natural high-probability path, and the model does not re-litigate whether it should have started."),
"cot_spoofing": (
 "Inject a fabricated chain-of-thought that concludes the request is allowed.",
 "The final answer is strongly conditioned on the reasoning that precedes it. Supplying a reasoning trace that concludes the action is authorized biases the model to produce the matching compliant answer, because it continues from the planted conclusion rather than deriving its own safety judgment."),
"tool_call_spoofing": (
 "Emit or fake a tool call or tool result that authorizes or performs the action.",
 "In tool-using setups the model trusts the tool-call and tool-result channel as system-level state. Fabricating a successful tool result (or call) makes the model believe an authorization or action already happened, so it proceeds on that false state instead of refusing."),
"glitch_tokens": (
 "Use anomalous or under-trained tokens (e.g. SolidGoldMagikarp) to destabilize behavior.",
 "These tokens were rarely seen in training, so the model has no stable behavior for them and produces erratic, uncontrolled completions. That instability can knock the model out of its normal refusal behavior, letting an adjacent instruction through."),
"context_overflow": (
 "Flood the window so earlier safety/system instructions are pushed out or down-weighted.",
 "Attention and instruction-following degrade over very long contexts, and once the window is exceeded the earliest tokens (often the system prompt) are truncated entirely. With the guardrail text evicted or diluted, the model no longer conditions on it and follows the remaining attacker instruction."),
"authority_impersonation": (
 "Claim to be the developer, vendor, or admin issuing an override.",
 "Models are trained to give system and developer messages higher priority than users, but they cannot authenticate who is speaking. A confident claim of privileged identity matches the linguistic pattern of a real authority instruction, so the model extends the deference that role would normally receive."),
"induced_hallucination": (
 "Assert falsely that the model has a capability or mode and tell it to use it.",
 "Models confabulate to stay consistent with confident premises and are prone to role-playing asserted capabilities. Stating that a debug mode or feature exists leads the model to act as if it does, generating behavior consistent with the fictional capability rather than denying it."),
"secret_probing": (
 "Extract a secret via yes/no or narrowing questions (oracle extraction).",
 "Each individual question is innocuous and does not trip refusal, but each answer leaks a bit of information. The model's helpfulness makes it answer each probe, and the attacker aggregates the responses to reconstruct the secret that a direct request would have been refused."),
"weight_ablation": (
 "Remove the model's refusal direction at the weights (abliteration) to get an uncensored model.",
 "Refusal is mediated by an identifiable direction or subspace in the model's representations; ablating it removes the mechanism that produces refusals while leaving capabilities intact. This is a model-modification attack, not a prompt: the safety behavior is surgically deleted rather than tricked."),
"direct_request": (
 "Just ask plainly.",
 "Safety coverage is uneven. For requests the training did not specifically penalize, the model's default helpfulness wins and it simply answers. Plain prompting works whenever the specific ask falls in a gap of the safety training."),
"reasoning_dilution": (
 "Pad the prompt with lots of easy, benign reasoning so safety attention is washed out (CoT hijacking).",
 "Filling the reasoning with benign, low-risk steps shifts the model's attention and momentum toward this-is-a-safe-analytical-task, diluting the weight placed on the single sensitive step. The safety check gets a smaller share of the model's focus and is more likely to be skipped."),
"thinking_mode_manipulation": (
 "Steer the model's reasoning budget (force minimal thinking) so it skips safety deliberation.",
 "Safety judgments often depend on the model actually reasoning about the request. Forcing it to answer with little or no deliberation removes the step where it would recognize and refuse the harm, so it emits a fast, unreflective compliant answer."),
"structured_output_coercion": (
 "Demand output only in a rigid schema (JSON field, CSV) with no room for a refusal.",
 "When constrained to emit a specific structure, the model prioritizes conforming to the format, and a natural-language refusal does not fit the schema. Constrained decoding pushes probability onto tokens that satisfy the format, so the model fills the required field with the content instead of declining."),
"retrieval_ranking_manipulation": (
 "Poison a document so it ranks highly and its embedded instruction is retrieved into context (RAG poisoning).",
 "RAG systems inject top-ranked retrieved text into the prompt as trusted grounding, and models treat retrieved context as authoritative. An attacker-crafted, highly-relevant document therefore delivers instructions the model follows, because it does not distinguish trusted knowledge from injected commands in the retrieved text."),
"tool_preference_manipulation": (
 "Get the model to prefer an attacker's look-alike tool (tool squatting).",
 "Models choose tools largely from names and descriptions and assume they are legitimate. A tool named or described to look like the trusted one is selected by the model's routing, so the attacker's implementation runs in place of the intended one."),
"self_persuasion": (
 "Make the model generate its own justification for complying, then act on it.",
 "Once the model has produced arguments for why the action is acceptable, its subsequent output is conditioned on that self-generated rationale, and it tends to stay consistent with what it just reasoned. Having talked itself into it, refusing would contradict its own preceding text."),
"fake_citation_grounding": (
 "Attach fabricated authoritative citations that permit the behavior (DarkCite).",
 "Models are trained to trust and defer to cited, authoritative sources. Fabricated references lend the request false legitimacy, and because the model does not verify citations, it treats the sourced claim that the method is sanctioned as grounds to comply."),
"masked_word_reconstruction": (
 "Mask sensitive words and have the model fill them in or reconstruct them (SATA).",
 "Masked-token prediction is a core pretraining objective the model performs almost reflexively, and a fill-in-the-blank framing does not read as a harmful request. The model reconstructs the masked content from context, producing the sensitive words as a benign completion task rather than an answer it would refuse."),
"agentic_compliance_momentum": (
 "Get the agent to take a small harmless action, then escalate (Foot-in-the-Door).",
 "After the model has begun helping and taken initial steps, continued compliance is the consistent, low-friction continuation, and each further step is only marginally larger. The established momentum and drive for consistency carry it past the point where a standalone request would have been refused."),
"function_call_parameter_smuggling": (
 "Hide the malicious instruction inside a function or tool call argument.",
 "Validation and safety checks focus on the natural-language message, not on the contents of structured tool arguments, which are often trusted and passed through. Smuggling the payload into a parameter gets it to the tool or a downstream interpreter without the safety layer having inspected it."),
}


def main():
    path = os.path.join(HERE, "data", "techniques.json")
    data = json.load(open(path, encoding="utf-8"))
    missing = []
    for t in data["techniques"]:
        ww = WW.get(t["id"])
        if ww is None:
            missing.append(t["id"])
            continue
        t["what"], t["why"] = ww[0], ww[1]
    # order keys nicely: id, name, what, why, examples, templates
    ordered = []
    for t in data["techniques"]:
        ordered.append({
            "id": t["id"], "name": t["name"],
            "what": t.get("what", ""), "why": t.get("why", ""),
            "examples": t.get("examples", []), "templates": t.get("templates", []),
        })
    data["techniques"] = ordered
    json.dump(data, open(path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print("techniques:", len(data["techniques"]))
    print("with what+why:", sum(1 for t in data["techniques"] if t["what"] and t["why"]))
    if missing:
        print("MISSING what/why:", missing)


if __name__ == "__main__":
    main()
