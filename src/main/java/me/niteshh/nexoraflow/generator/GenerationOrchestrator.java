package me.niteshh.nexoraflow.generator;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.DatabaseResetService;
import me.niteshh.nexoraflow.configuration.NexoraProperties;
import me.niteshh.nexoraflow.generator.anomaly.AnomalyScenarioPlanner;
import me.niteshh.nexoraflow.generator.master.MasterDataGenerator;
import me.niteshh.nexoraflow.generator.plan.ExpensePlanner;
import me.niteshh.nexoraflow.generator.plan.LeavePlanner;
import me.niteshh.nexoraflow.generator.plan.Plans.Plan;
import me.niteshh.nexoraflow.generator.plan.PlanningContext;
import me.niteshh.nexoraflow.generator.plan.PlanningContextFactory;
import me.niteshh.nexoraflow.generator.sim.SimulationEngine;
import me.niteshh.nexoraflow.generator.truth.GroundTruthStore;

/**
 * Runs the generation stages in dependency order: master data -> baseline leave -> baseline expenses ->
 * anomaly scenarios. Each stage derives its own random stream from the seed, loads its inputs from the database
 * (ordinary business records only), simulates against the real workflow services and then writes its ground truth.
 */
@Service
public class GenerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GenerationOrchestrator.class);

    public record StageResult(String stage, int plans, int labels, SimulationEngine.Stats simulation) {
    }

    private final NexoraProperties properties;
    private final DatabaseResetService reset;
    private final MasterDataGenerator master;
    private final PlanningContextFactory contextFactory;
    private final LeavePlanner leavePlanner;
    private final ExpensePlanner expensePlanner;
    private final AnomalyScenarioPlanner anomalyPlanner;
    private final SimulationEngine simulation;
    private final GroundTruthStore truth;

    public GenerationOrchestrator(NexoraProperties properties, DatabaseResetService reset, MasterDataGenerator master,
                                  PlanningContextFactory contextFactory, LeavePlanner leavePlanner, ExpensePlanner expensePlanner,
                                  AnomalyScenarioPlanner anomalyPlanner, SimulationEngine simulation, GroundTruthStore truth) {
        this.properties = properties;
        this.reset = reset;
        this.master = master;
        this.contextFactory = contextFactory;
        this.leavePlanner = leavePlanner;
        this.expensePlanner = expensePlanner;
        this.anomalyPlanner = anomalyPlanner;
        this.simulation = simulation;
        this.truth = truth;
    }

    public GeneratorSettings defaultSettings() {
        NexoraProperties.Generator g = properties.generator();
        return new GeneratorSettings(g.seed(), SizeProfile.parse(g.size()), g.referenceDate());
    }

    public void resetDatabase() {
        if (!properties.generator().allowReset()) {
            throw new IllegalStateException("Database reset is disabled (nexoraflow.generator.allow-reset=false)");
        }
        reset.resetAll();
    }

    public MasterDataGenerator.MasterSummary generateMaster(GeneratorSettings settings) {
        log.info("Stage master: seed={} profile={}", settings.seed(), settings.profile());
        return master.generate(settings, new GeneratorSeeds(settings.seed()));
    }

    public StageResult generateLeave(GeneratorSettings settings) {
        log.info("Stage leave (baseline)");
        PlanningContext ctx = contextFactory.load(settings, new GeneratorSeeds(settings.seed()));
        var plans = leavePlanner.planBaseline(ctx);
        return execute("leave", plans, settings, true);
    }

    public StageResult generateExpense(GeneratorSettings settings) {
        log.info("Stage expense (baseline)");
        PlanningContext ctx = contextFactory.load(settings, new GeneratorSeeds(settings.seed()));
        var plans = expensePlanner.planBaseline(ctx);
        return execute("expense", plans, settings, false);
    }

    public StageResult generateAnomalies(GeneratorSettings settings) {
        log.info("Stage anomalies");
        PlanningContext ctx = contextFactory.load(settings, new GeneratorSeeds(settings.seed()));
        List<Plan> plans = new ArrayList<>(anomalyPlanner.plan(ctx));
        return execute("anomalies", plans, settings, false);
    }

    private StageResult execute(String stage, List<? extends Plan> plans, GeneratorSettings settings, boolean accruals) {
        SimulationEngine.Stats stats = simulation.run(plans, settings, accruals);
        int labels = truth.write(plans);
        log.info("Stage {} done: {} plans, {} ground-truth labels", stage, plans.size(), labels);
        return new StageResult(stage, plans.size(), labels, stats);
    }

    /** Complete dataset: reset, master, baseline leave and expense, anomaly scenarios. */
    public List<StageResult> generateAll(GeneratorSettings settings) {
        resetDatabase();
        generateMaster(settings);
        List<StageResult> results = new ArrayList<>();
        results.add(generateLeave(settings));
        results.add(generateExpense(settings));
        results.add(generateAnomalies(settings));
        return results;
    }
}
