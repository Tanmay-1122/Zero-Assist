import { cn } from "../../lib/cn";

interface GrainProps {
    className?: string;
}

/** Fixed film-grain overlay. Purely decorative. */
export function Grain({ className }: GrainProps) {
    return <div className={cn("grain", className)} aria-hidden="true" />;
}
