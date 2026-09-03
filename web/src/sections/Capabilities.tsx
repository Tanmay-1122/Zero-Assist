import { capabilities } from "../lib/content";
import { type AssetKey } from "../lib/assets";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { Reveal } from "../components/motion/Reveal";
import { AssetFrame } from "../components/media/AssetFrame";
import { cn } from "../lib/cn";

function CapThumb({ tone }: { tone: string }) {
    return (
        <div
            className={cn(
                "cap-thumb",
                tone === "gold" && "cap-thumb--gold",
                tone === "warm" && "cap-thumb--warm",
            )}
        />
    );
}

export function Capabilities() {
    return (
        <section className="section" id="capabilities" aria-labelledby="capabilities-title">
            <Container className="section__inner">
                <div className="cap-header">
                    <div>
                        <Reveal>
                            <Kicker index="02">Capabilities</Kicker>
                        </Reveal>
                        <Reveal delay={80}>
                            <h2 className="cap-header__title" id="capabilities-title">
                                One assistant, <em className="grad-text">six native powers.</em>
                            </h2>
                        </Reveal>
                    </div>
                    <Reveal delay={140} className="cap-header__desc">
                        <p>
                            Not a thin wrapper over an API — a resident agent with deep,
                            permissioned access to the device it lives on.
                        </p>
                    </Reveal>
                </div>

                <div className="cap-list">
                    {capabilities.map((cap, i) => (
                        <Reveal key={cap.id} delay={i * 60}>
                            <article className="cap-row" aria-label={cap.title} tabIndex={0}>
                                <div className="cap-row__index" aria-hidden="true">
                                    {cap.index}
                                </div>
                                <div className="cap-row__content">
                                    <h3 className="cap-row__title">{cap.title}</h3>
                                    <p className="cap-row__desc">{cap.desc}</p>
                                </div>
                                <div className="cap-row__media">
                                    {/* TODO: Replace with generated capability artwork */}
                                    <AssetFrame
                                        slot={cap.slot as AssetKey}
                                        className="cap-row__media-frame"
                                    >
                                        <CapThumb tone={cap.tone} />
                                    </AssetFrame>
                                </div>
                            </article>
                        </Reveal>
                    ))}
                </div>
            </Container>
        </section>
    );
}
