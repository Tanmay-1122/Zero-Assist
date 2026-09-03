import { openSource, site } from "../lib/content";
import { cn } from "../lib/cn";
import { Button } from "../components/ui/Button";
import { Container } from "../components/ui/Container";
import { Kicker } from "../components/ui/Kicker";
import { Reveal } from "../components/motion/Reveal";

export function OpenSource() {
    const release = openSource.release;

    return (
        <section className="section" id="download" aria-labelledby="oss-title">
            <Container className="oss__grid section__inner">
                <div>
                    <Reveal>
                        <Kicker index="06" tone="gold">
                            Open source
                        </Kicker>
                    </Reveal>
                    <Reveal delay={80}>
                        <h2 className="oss__title" id="oss-title">
                            {openSource.title}
                        </h2>
                    </Reveal>
                    <Reveal delay={140}>
                        <p className="oss__copy">{openSource.copy}</p>
                    </Reveal>

                    <Reveal delay={200}>
                        <div className="oss__links">
                            <Button href={site.repoUrl}>{openSource.primaryCta}</Button>
                            <Button variant="ghost" href={site.contributingUrl}>
                                {openSource.ghostCta}
                            </Button>
                        </div>
                    </Reveal>

                    <Reveal delay={260}>
                        <div className="oss__badges">
                            {openSource.badges.map((badge) => (
                                <span className="oss__badge" key={badge}>
                                    {badge}
                                </span>
                            ))}
                        </div>
                    </Reveal>
                </div>

                <Reveal delay={180}>
                    <div className="release">
                        <div className="release__head">
                            <span className="release__name">{release.name}</span>
                            <span className="release__status">{release.status}</span>
                        </div>
                        <p className="release__meta">{release.meta}</p>

                        <div className="release__abis">
                            {release.abis.map((abi) => (
                                <div
                                    key={abi.arch}
                                    className={cn(
                                        "release__abi",
                                        abi.primary && "release__abi--primary",
                                    )}
                                >
                                    <span className="release__abi-arch">{abi.arch}</span>
                                    <span className="release__abi-role">{abi.role}</span>
                                </div>
                            ))}
                        </div>

                        <div className="release__actions">
                            <Button href={site.repoUrl} size="lg">
                                {release.action}
                            </Button>
                        </div>
                        <p className="release__note">{release.note}</p>
                    </div>
                </Reveal>
            </Container>
        </section>
    );
}
