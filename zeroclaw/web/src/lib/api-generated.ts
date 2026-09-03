// GENERATED stub — minimal types to satisfy tsc. Replace with
// `npx openapi-typescript` output when the OpenAPI spec is available.
export type components = {
  schemas: {
    ConfigApiCode: string;
    SlashOptionKindDescriptor: {
      manifest_name: string;
      supports_numeric_bounds: boolean;
      supports_length_bounds: boolean;
      supports_choices: boolean;
    };
    Sop: {
      name: string;
      description: string;
      version: string;
      priority: Schemas["SopPriority"];
      execution_mode: Schemas["SopExecutionMode"];
      agent?: string | null;
      triggers: Schemas["SopTrigger"][];
      steps: Schemas["SopStep"][];
      cooldown_secs?: number | null;
      max_concurrent?: number | null;
      deterministic?: boolean;
    };
    SopStep: {
      number: number;
      title: string;
      body: string;
      kind: string;
      agent?: string | null;
      requires_confirmation?: boolean;
      suggested_tools?: string[];
      routing?: Schemas["StepRouting"] | null;
      on_failure?: Schemas["StepFailure"];
      calls?: Schemas["PlannedToolCall"][];
      schema?: Schemas["StepSchema"] | null;
      pos?: { x: number; y: number } | null;
    };
    SopTrigger:
      | {
          type: "channel";
          channel: string;
          alias: string | null;
          condition: string | null;
        }
      | { type: "manual" };
    SopPriority: string;
    SopExecutionMode: string;
    SopStepKind: string;
    StepRouting: {
      depends_on?: number[];
      next?: number | null;
      when?: string | null;
      switch?: Schemas["SwitchRule"][];
    };
    SwitchRule: {
      name: string;
      when?: string | null;
      goto?: number;
    };
    StepFailure:
      | "fail"
      | { retry: { max: number } }
      | { goto: { step: number } };
    StepSchema: {
      input?: Record<string, unknown>;
    };
    StepToolScope: string;
    PlannedToolCall: {
      tool: string;
      args?: unknown;
      pinned?: unknown;
    };
    StepToolCall: {
      index: number;
      tool?: string;
      args?: Record<string, unknown>;
      output?: string | null;
      output_data?: unknown;
      success?: boolean;
      duration_ms?: number | null;
      error?: string | null;
    };
    SopGraph: {
      nodes: Schemas["GraphNode"][];
      wires: Schemas["GraphWire"][];
      layout: Schemas["GraphLayout"];
      diagnostics: Schemas["GraphDiagnostic"][];
    };
    GraphNode: {
      step: number;
      kind: string;
      title: string;
      subtitle?: string;
      inputs: Schemas["GraphPin"][];
      outputs: Schemas["GraphPin"][];
      trigger_index?: number | null;
    };
    GraphPin: {
      class: string;
      name: string;
      data_type?: string | null;
      required?: boolean;
    };
    GraphWire: {
      class: string;
      from_step: number;
      to_step: number;
      flow_role?: string | null;
      from_pin: string;
      to_pin: string;
    };
    GraphDiagnostic: {
      severity: string;
      step: number;
      message: string;
    };
    GraphLayout: {
      geometry: Schemas["LayoutGeometry"];
      positions: Schemas["NodePosition"][];
    };
    LayoutGeometry: {
      node_w: number;
      node_h: number;
      col_gap: number;
      row_gap: number;
      origin: number;
    };
    NodePosition: {
      step: number;
      x?: number | null;
      y?: number | null;
      col: number;
      row: number;
    };
    NodeKind: string;
    FlowRole: string;
    GraphSeverity: string;
    RunOverlay: {
      status: Schemas["SopRunStatus"];
      current_step: number;
      total_steps: number;
      waiting?: boolean;
      paused?: boolean;
      nodes: Schemas["NodeRunOverlay"][];
    };
    ApprovalDecision:
      | "approve"
      | { deny: { reason?: string } };
    NodeRunOverlay: {
      step: number;
      state: Schemas["NodeRunState"];
      tool_calls?: Schemas["StepToolCall"][];
    };
    NodeRunState: string;
    SopRunStatus: string;
    TriggerSourceRegistry: {
      channels: Schemas["ChannelTriggerKind"][];
      bound: Schemas["BoundTriggerSource"][];
      sources: string[];
      operators: Schemas["ConditionOpSpec"][];
    };
    BoundTriggerSource: {
      source: string;
      label?: string;
      fields: Schemas["TriggerField"][];
      condition?: Schemas["PayloadContract"] | null;
    };
    TriggerField: {
      kind: string;
      name: string;
      options?: string[];
      multi?: boolean;
    };
    ChannelTriggerKind: {
      channel: string;
      configured: boolean;
      aliases: Schemas["ChannelAlias"][];
      setup_path: string;
      condition?: Schemas["PayloadContract"] | null;
    };
    ChannelAlias: {
      alias: string;
    };
    PayloadContract: {
      fields?: Schemas["ConditionField"][];
      direct?: boolean;
      open?: boolean;
    };
    ConditionField: {
      path: string;
      label: string;
      value_type: string;
      options?: string[] | null;
    };
    ConditionOpSpec: {
      token: string;
      label: string;
    };
    ConditionValueType: string;
    GraphLegend: {
      flow_roles?: Schemas["LegendEntry"][];
      pin_classes: Schemas["LegendEntry"][];
      run_states?: Schemas["LegendEntry"][];
    };
    LegendEntry: {
      key: string;
      label: string;
      description: string;
    };
  };
};

type Schemas = components["schemas"];
