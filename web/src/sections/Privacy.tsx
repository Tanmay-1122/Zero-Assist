import { privacy } from "../lib/content";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { Reveal } from "../components/motion/Reveal";
import { AssetFrame } from "../components/media/AssetFrame";

/** Procedural vault — concentric layers with a sealed core. */
function Vault() {
    return (
        <div className="vault" aria-hidden="true">
            <div className="vault__layer vault__layer--1" />
            <div className="vault__layer vault__layer--2" />
            <div className="vault__layer vault__layer--3">
                <div className="vault__core" />
            </div>
        </div>
    );
}

export function Privacy() {
    return (
        <section className="section privacy" id="privacy" aria-labelledby="privacy-title">
            <div className="privacy__bg" aria-hidden="true" />

            <Container className="privacy__grid section__inner">
                <div>
                    <Reveal>
                        <Kicker index="04">Privacy</Kicker>
                    </Reveal>
                    <Reveal delay={80}>
                        <h2 className="privacy__title" id="privacy-title">
                            Your phone is <em className="grad-text">{privacy.accent}</em>
                        </h2>
                    </Reveal>
                    <Reveal delay={140}>
                        <p className="privacy__copy">{privacy.copy}</p>
                    </Reveal>

                    <div className="privacy__points">
                        {privacy.points.map((point, i) => (
                            <Reveal key={point.title} delay={160 + i * 60}>
                                <div className="privacy__point">
                                    <span className="privacy__point-icon" aria-hidden="true">
                                        {point.icon}
                                    </span>
                                    <span className="privacy__point-title">{point.title}</span>
                                    <p className="privacy__point-desc">{point.desc}</p>
                                </div>
                            </Reveal>
                        ))}
                    </div>
                </div>

                <Reveal delay={200}>
                    {/* TODO: Replace with generated vault artwork */}
                    <AssetFrame slot="privacy-vault" className="privacy__vault">
                        <Vault />
                    </AssetFrame>
                </Reveal>
            </Container>
        </section>
    );
}
