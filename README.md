# CKD Prediction Dataset

This repository contains a chronic kidney disease dataset and a Java
preprocessor that prepares the data for prediction workflows.

## Contents

- `CKDPreprocessor.java`: reads the ARFF dataset, imputes missing numeric and
  categorical values, caps numeric outliers using IQR fences, and writes CSV
  output.
- `Chronic_Kidney_Disease/chronic_kidney_disease.arff`: source ARFF dataset.
- `Chronic_Kidney_Disease/chronic_kidney_disease_full.arff`: full ARFF dataset
  supplied with the project.
- `Chronic_Kidney_Disease/ckd_preprocessed.csv`: generated cleaned dataset.
- `Chronic_Kidney_Disease/preprocessing_report.csv`: numeric preprocessing
  summary.
- `Chronic_Kidney_Disease/chronic_kidney_disease.info.txt`: dataset metadata.

## Requirements

- Java 11 or newer

## Run the preprocessor

From the repository root:

```powershell
javac CKDPreprocessor.java
java CKDPreprocessor
```

The command updates `ckd_preprocessed.csv` and `preprocessing_report.csv` in
the `Chronic_Kidney_Disease` directory.