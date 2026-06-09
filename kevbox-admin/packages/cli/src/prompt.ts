import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";

/** A single question -> answer function. Actions take this as a parameter so tests can stub it. */
export type Ask = (question: string) => Promise<string>;

/** Real readline-backed Ask. Opens and closes one readline interface per call. */
export const ask: Ask = async (question: string): Promise<string> => {
  const rl = createInterface({ input, output });
  try {
    return (await rl.question(question)).trim();
  } finally {
    rl.close();
  }
};

/** Prompt for a yes/no confirmation; only a literal "y"/"yes" (case-insensitive) confirms. */
export async function confirm(askFn: Ask, question: string): Promise<boolean> {
  const answer = (await askFn(`${question} [y/N] `)).toLowerCase();
  return answer === "y" || answer === "yes";
}
