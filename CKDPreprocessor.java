import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CKDPreprocessor {
    private static final Path INPUT = Path.of("Chronic_Kidney_Disease", "chronic_kidney_disease.arff");
    private static final Path OUTPUT = Path.of("Chronic_Kidney_Disease", "ckd_preprocessed.csv");
    private static final Path REPORT = Path.of("Chronic_Kidney_Disease", "preprocessing_report.csv");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "^@attribute\\s+(?:'([^']+)'|(\\S+))\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private static final class Dataset {
        final List<String> columns;
        final boolean[] numeric;
        final Map<Integer, Set<String>> allowedValues;
        final List<List<String>> rows;

        Dataset(List<String> columns, boolean[] numeric, Map<Integer, Set<String>> allowedValues,
                List<List<String>> rows) {
            this.columns = columns;
            this.numeric = numeric;
            this.allowedValues = allowedValues;
            this.rows = rows;
        }
    }

    private static final class NumericReport {
        final String column;
        final double mean;
        final int missing;
        double lowerFence;
        double upperFence;
        int capped;

        NumericReport(String column, double mean, int missing) {
            this.column = column;
            this.mean = mean;
            this.missing = missing;
        }
    }

    public static void main(String[] args) throws IOException {
        Dataset dataset = readArff(INPUT);
        List<Integer> numericIndices = new ArrayList<>();
        List<NumericReport> report = new ArrayList<>();

        for (int index = 0; index < dataset.columns.size(); index++) {
            if (!dataset.numeric[index]) {
                continue;
            }
            numericIndices.add(index);
            double sum = 0.0;
            int observed = 0;
            int missing = 0;
            for (List<String> row : dataset.rows) {
                if (row.get(index).equals("?")) {
                    missing++;
                } else {
                    sum += Double.parseDouble(row.get(index));
                    observed++;
                }
            }
            double mean = sum / observed;
            for (List<String> row : dataset.rows) {
                if (row.get(index).equals("?")) {
                    row.set(index, format(mean));
                }
            }
            report.add(new NumericReport(dataset.columns.get(index), mean, missing));
        }

        for (int index = 0; index < dataset.columns.size(); index++) {
            if (dataset.numeric[index]) {
                continue;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (List<String> row : dataset.rows) {
                String value = row.get(index);
                if (!value.equals("?")) {
                    counts.merge(value, 1, Integer::sum);
                }
            }
            if (counts.isEmpty()) {
                throw new IllegalArgumentException("Column '" + dataset.columns.get(index)
                        + "' contains only missing values.");
            }
            String mode = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow()
                    .getKey();
            for (List<String> row : dataset.rows) {
                if (row.get(index).equals("?")) {
                    row.set(index, mode);
                }
            }
        }

        for (int reportIndex = 0; reportIndex < report.size(); reportIndex++) {
            int columnIndex = numericIndices.get(reportIndex);
            List<Double> values = new ArrayList<>();
            for (List<String> row : dataset.rows) {
                values.add(Double.parseDouble(row.get(columnIndex)));
            }
            Collections.sort(values);
            double q1 = percentile(values, 0.25);
            double q3 = percentile(values, 0.75);
            double iqr = q3 - q1;
            NumericReport item = report.get(reportIndex);
            item.lowerFence = q1 - 1.5 * iqr;
            item.upperFence = q3 + 1.5 * iqr;
            for (List<String> row : dataset.rows) {
                double value = Double.parseDouble(row.get(columnIndex));
                if (value < item.lowerFence) {
                    row.set(columnIndex, format(item.lowerFence));
                    item.capped++;
                } else if (value > item.upperFence) {
                    row.set(columnIndex, format(item.upperFence));
                    item.capped++;
                }
            }
        }

        writeCsv(OUTPUT, dataset.columns, dataset.rows);
        writeReport(REPORT, report);
        System.out.println("Processed " + dataset.rows.size() + " records; saved " + OUTPUT + ".");
        for (NumericReport item : report) {
            System.out.printf("%s mean=%.4f missing=%d capped=%d range=[%.4f, %.4f]%n",
                    item.column, item.mean, item.missing, item.capped,
                    item.lowerFence, item.upperFence);
        }
    }

    private static Dataset readArff(Path path) throws IOException {
        List<String> columns = new ArrayList<>();
        List<Boolean> numericTypes = new ArrayList<>();
        Map<Integer, Set<String>> allowedValues = new HashMap<>();
        List<List<String>> rows = new ArrayList<>();
        boolean inData = false;

        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("%")) {
                continue;
            }
            if (line.equalsIgnoreCase("@data")) {
                inData = true;
                continue;
            }
            if (!inData) {
                Matcher matcher = ATTRIBUTE.matcher(line);
                if (matcher.matches()) {
                    String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    String definition = matcher.group(3).trim();
                    columns.add(name);
                    numericTypes.add(definition.equalsIgnoreCase("numeric"));
                    if (definition.startsWith("{")) {
                        String values = definition.substring(1, definition.lastIndexOf('}'));
                        Set<String> allowed = new HashSet<>();
                        for (String value : values.split(",")) {
                            allowed.add(value.trim());
                        }
                        allowedValues.put(columns.size() - 1, allowed);
                    }
                }
                continue;
            }

            String[] fields = line.split(",", -1);
            List<String> row = new ArrayList<>();
            for (String field : fields) {
                row.add(field.trim());
            }
            while (row.size() > columns.size() && row.get(row.size() - 1).isEmpty()) {
                row.remove(row.size() - 1);
            }
            if (row.size() == columns.size() + 1 && columns.size() == 25
                    && row.get(19).isEmpty() && allowedValues.get(21).contains(row.get(22))) {
                row.remove(21);
            }
            if (row.size() == columns.size() + 1) {
                List<List<String>> candidates = new ArrayList<>();
                for (int removeIndex = 0; removeIndex < row.size(); removeIndex++) {
                    List<String> candidate = new ArrayList<>(row);
                    candidate.remove(removeIndex);
                    if (isValidRow(candidate, numericTypes, allowedValues)) {
                        candidates.add(candidate);
                    }
                }
                if (uniqueCandidates(candidates).size() == 1) {
                    row = new ArrayList<>(uniqueCandidates(candidates).iterator().next());
                }
            }
            for (int index = 0; index < row.size(); index++) {
                if (row.get(index).isEmpty()) {
                    row.set(index, "?");
                }
            }
            rows.add(row);
        }

        List<String> mismatched = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).size() != columns.size()) {
                mismatched.add((index + 1) + ":" + rows.get(index).size());
            }
        }
        if (rows.isEmpty() || !mismatched.isEmpty()) {
            throw new IllegalArgumentException("ARFF rows do not match declared attributes: " + mismatched);
        }
        boolean[] numeric = new boolean[numericTypes.size()];
        for (int index = 0; index < numeric.length; index++) {
            numeric[index] = numericTypes.get(index);
        }
        return new Dataset(columns, numeric, allowedValues, rows);
    }

    private static boolean isValidRow(List<String> row, List<Boolean> numeric,
            Map<Integer, Set<String>> allowedValues) {
        for (int index = 0; index < row.size(); index++) {
            String value = row.get(index);
            if (value.isEmpty() || value.equals("?")) {
                continue;
            }
            if (numeric.get(index)) {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException exception) {
                    return false;
                }
            } else if (!allowedValues.getOrDefault(index, Collections.emptySet()).contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static Set<List<String>> uniqueCandidates(List<List<String>> candidates) {
        return new HashSet<>(candidates);
    }

    private static double percentile(List<Double> sortedValues, double fraction) {
        double position = (sortedValues.size() - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        return sortedValues.get(lower) + (sortedValues.get(upper) - sortedValues.get(lower))
                * (position - lower);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.10g", value);
    }

    private static void writeCsv(Path path, List<String> columns, List<List<String>> rows)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns));
            writer.newLine();
            for (List<String> row : rows) {
                writer.write(String.join(",", row));
                writer.newLine();
            }
        }
    }

    private static void writeReport(Path path, List<NumericReport> report) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("column,mean_before_imputation,missing_values_imputed,lower_iqr_fence,upper_iqr_fence,outlier_values_capped");
            writer.newLine();
            for (NumericReport item : report) {
                writer.write(String.format(java.util.Locale.ROOT, "%s,%.10g,%d,%.10g,%.10g,%d%n",
                        item.column, item.mean, item.missing, item.lowerFence, item.upperFence,
                        item.capped));
            }
        }
    }
}