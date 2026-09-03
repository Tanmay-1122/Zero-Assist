import { cta, site } from "../lib/content";
import { Button } from "../components/ui/Button";
import { Container } from "../components/ui/Container";
import { Reveal } from "../components/motion/Reveal";

export function CTA() {
    return (
        <section className="section cta" aria-labelledby="cta-title">
            <div className="cta__bg" aria-hidden="true" />
            <span className="cta__watermark" aria-hidden="true">
                {cta.watermark}
            </span>

            <Container className="section__inner">
                <Reveal>
                    <h2 className="cta__title" id="cta-title">
                        {cta.titleBefore} <em className="grad-text">{cta.accent}</em>
                    </h2>
                </Reveal>

                <Reveal delay={120}>
                    <div className="cta__actions">
                        <Button href={site.repoUrl} size="lg">
                            {cta.primaryCta}
                        </Button>
                        <Button variant="ghost" size="lg" href={site.docsUrl}>
                            {cta.ghostCta}
                        </Button>
                    </div>
                </Reveal>

                <Reveal delay={200}>
                    <p className="cta__note">{cta.note}</p>
                </Reveal>
            </Container>
        </section>
    );
}
