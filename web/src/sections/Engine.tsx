import { engine } from "../lib/content";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { Reveal } from "../components/motion/Reveal";

export function Engine() {
    return (
        <section className="section" id="engine" aria-labelledby="engine-title">
            <Container className="engine section__inner">
                <div className="engine__intro">
                    <Reveal>
                        <Kicker index="03" tone="gold">
                            The engine
                        </Kicker>
                    </Reveal>
                    <Reveal delay={80}>
                        <h2 className="engine__title" id="engine-title">
                            {engine.title}
                        </h2>
                    </Reveal>
                    <Reveal delay={140}>
                        <p className="engine__copy">{engine.copy}</p>
                    </Reveal>

                    <div className="engine__stats">
                        {engine.stats.map((stat, i) => (
                            <Reveal key={stat.label} delay={160 + i * 60}>
                                <div className="engine__stat">
                                    <div className="engine__stat-value">{stat.value}</div>
                                    <div className="engine__stat-label">{stat.label}</div>
                                </div>
                            </Reveal>
                        ))}
                    </div>
                </div>

                <div className="engine__rail-wrap">
                    <Reveal delay={80}>
                        <p className="engine__rail-label">{engine.railLabel}</p>
                    </Reveal>
                    <div className="engine__rail">
                        {engine.nodes.map((node, i) => (
                            <Reveal
                                key={node.name}
                                delay={120 + i * 100}
                                className="engine__node-wrap"
                            >
                                <div className="engine__node">
                                    <div className="engine__node-head">
                                        <span className="engine__node-name">{node.name}</span>
                                        <span className="engine__node-tag">{node.tag}</span>
                                    </div>
                                    <p className="engine__node-desc">{node.desc}</p>
                                </div>
                            </Reveal>
                        ))}
                    </div>
                </div>
            </Container>
        </section>
    );
}
