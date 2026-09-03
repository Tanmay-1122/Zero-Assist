import { manifesto } from "../lib/content";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { Reveal } from "../components/motion/Reveal";
import { WordMask } from "../components/motion/WordMask";

export function Manifesto() {
    return (
        <section className="section manifesto" id="manifesto" aria-labelledby="manifesto-title">
            <Container variant="narrow" className="section__inner">
                <Reveal>
                    <Kicker index="01" tone="gold">
                        Manifesto
                    </Kicker>
                </Reveal>

                <div className="manifesto__statement" id="manifesto-title">
                    <WordMask
                        text={manifesto.statement}
                        accentWords={manifesto.accentWords}
                        startDelay={120}
                        stepDelay={70}
                    />
                </div>

                <div className="manifesto__foot">
                    <Reveal delay={140}>
                        <p>{manifesto.foot}</p>
                    </Reveal>
                    <Reveal delay={220}>
                        <div className="manifesto__sig">{manifesto.signature}</div>
                    </Reveal>
                </div>
            </Container>
        </section>
    );
}
