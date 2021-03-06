package nl.uva;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.biojava.bio.BioException;
import org.biojava3.alignment.Alignments;
import org.biojava3.alignment.template.Profile;
import org.biojava3.core.sequence.ProteinSequence;
import org.biojava3.core.sequence.compound.AminoAcidCompound;
import org.biojava3.core.util.ConcurrencyTools;
import org.biojavax.SimpleNamespace;
import org.biojavax.bio.seq.RichSequence;

/**
 * This is a mapreduce implementation of a sequence aliment. 
 * The code first performs a pairwise-alignment between the query sequences, 
 * finds the N most similar sequences (using mapreduce)  and then it performs 
 * multi-sequence alignment between the query sequence and the N most similar sequences.
 *
 * @author S. Koulouzis
 */
public class AssignmentMapreduce extends Configured implements Tool {

    public static final String NUMBER_OF_RELATIVE_NAME = "numer.of.relative";
    private String queryProteinFile;
    static Log log = LogFactory.getLog(AssignmentMapreduce.class);

    public static void main(String[] args) {
        try {
            if (args == null || args.length < 5 || args[0].equals("-help") || args[0].equals("help")) {
                printHelp();
                System.exit(-1);
            }

            String queryProteinFile = args[0];
            String datasetFile = args[1];
            String substitutionMatrixFile = args[2];
            String numberOfrelative = args[3];
            String outputFolder = args[4];
            String optinalArgName = null;
            String optinalArg = null;

            String[] myArgs;
            if (args.length >= 6) {
                optinalArgName = args[5];
                optinalArg = args[6];
                myArgs = new String[]{queryProteinFile, datasetFile, substitutionMatrixFile, numberOfrelative, outputFolder, optinalArgName, optinalArg};
            } else {
                myArgs = new String[]{queryProteinFile, datasetFile, substitutionMatrixFile, numberOfrelative, outputFolder};
            }

            //Pass the arguments 
            int res = ToolRunner.run(new Configuration(), new AssignmentMapreduce(), myArgs);

            System.exit(res);
        } catch (Exception ex) {
            printHelp();
            Logger.getLogger(AssignmentMapreduce.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: <query protein file> <dataset file> <substitution matrix file> <number Of relative proteins> <outpout folder> <optional arguments>\n");
        System.out.println("query protein file :    The query protein fasta file. If this file contains more than one sequences the code will load only the first.\n"
                + "dataset file:                    The dataset fasta file containing the sequences we want to find the compare the query sequence with.\n"
                + "substitution matrix file:        Is the file containing the substitution matrix for initilizing the Needleman-Wunsch alignment algorithm.\n"
                + "number Of relative proteins :    The number of the higest scoring sequences we want to allign.\n"
                + "output folder :                  The location where the results will be saved.\n"
                + "optinal argumants:               Optional argumants \n"
                + "          -recordsPerSplit:      Controls the domain decomposition. The number of protein sequences to be added per file split. \n"
                + "                                 If for example the dataset file contains 200 sequences and we set this value to 100 we will create two (200/100) file splits, and by extension two mappers.\n");
    }

    @Override
    public int run(String[] args) throws Exception {
        //Create the configuration 
        JobConf jobConf = configureJob(args);

        //Run the job
        JobClient.runJob(jobConf);

        //Get the results and do the multi alignment
        doMultiAlignment(jobConf);

        return 0;
    }

    private JobConf configureJob(String[] args) {
        JobConf conf = new JobConf(getConf(), AssignmentMapreduce.class);
        conf.setJobName("ProtainAlign");

        //Define the class for the output key. Here we need to implement our own 
        //WritableComparable, because the IntWritable emits the values in 
        //ascending order while we need them in descending order if we want to 
        //get the maximum scores from the alignment 
        conf.setOutputKeyClass(ScoreWritable.class);
        //Define the class for the output key. Here we can use the Text class, 
        //although it would be practical to define a Protein sequence class to 
        //to be able to get more information (like names, URN, etc) from the 
        //sequences in the Reduce phase 
        conf.setOutputValueClass(Text.class);

        //Set the Maper and Combiner classes
        conf.setMapperClass(nl.uva.Map.class);
        conf.setCombinerClass(nl.uva.Reduce.class);
        conf.setReducerClass(nl.uva.Reduce.class);

        //Set the input format as FastaInputFormat, so we can read FASTA files
        conf.setInputFormat(FastaInputFormat.class);
        conf.setOutputFormat(TextOutputFormat.class);

        queryProteinFile = args[0];
        String datasetFile = args[1];
        String substitutionMatrixFile = args[2];
        int numberOfrelative = Integer.valueOf(args[3]);
        String outputFolder = args[4];
        if (args.length >= 6 && args[5].equals("-recordsPerSplit")) {
            conf.setInt("records.per.split", Integer.valueOf(args[6]));
        }
        //Add the query and matrix files to the distributed cache
        DistributedCache.addCacheFile(new Path(queryProteinFile).toUri(), conf);
        DistributedCache.addCacheFile(new Path(substitutionMatrixFile).toUri(), conf);
        FastaInputFormat.setInputPaths(conf, new Path(datasetFile));
        conf.setInt(NUMBER_OF_RELATIVE_NAME, numberOfrelative);

        //Set the output folder 
        FileOutputFormat.setOutputPath(conf, new Path(outputFolder));

        return conf;
    }

    private void doMultiAlignment(JobConf jobConf) throws IOException, NoSuchElementException, BioException {
        //Get the path where we will save the output
        Path output = FileOutputFormat.getOutputPath(jobConf);

        //Get the query protein from the file
        FileSystem fs = FileSystem.get(jobConf);
        HashMap<ProteinSequence, Integer> topSequences = new HashMap<ProteinSequence, Integer>();

        DataInputStream d = new DataInputStream(fs.open(new Path(queryProteinFile)));
        BufferedReader br = new BufferedReader(new InputStreamReader(d));
        SimpleNamespace ns = new SimpleNamespace("biojava");
        RichSequence query = RichSequence.IOTools.readFastaProtein(br, ns).nextRichSequence();

        //Get the results from the Reduce phase from the output path 
        log.info("Score\tSequence");
        log.info("__________________________");
        Path outPutPath = FileOutputFormat.getOutputPath(jobConf);
        FileStatus[] files = fs.globStatus(new Path(outPutPath + "/part-*"));
        for (FileStatus file : files) {
            log.info(file.getPath().toUri());
            if (file.getLen() > 0) {
                FSDataInputStream in = fs.open(file.getPath());
                BufferedReader bin = new BufferedReader(new InputStreamReader(
                        in));
                String s = bin.readLine();
                while (s != null) {
                    String[] scoreVal = s.split("\t");
                    topSequences.put(new ProteinSequence(scoreVal[1]), Integer.valueOf(scoreVal[0]));
                    log.info(scoreVal[0] + "\t" + scoreVal[1]);
                    s = bin.readLine();
                }
                in.close();
            }
        }

        //Add the query sequence so we can do the multialignment
        topSequences.put(new ProteinSequence(query.seqString()), Integer.MAX_VALUE);

        //Sort the results by value so we can align from highest to lowest score
        ValueComparator vc = new ValueComparator(topSequences);
        TreeMap<ProteinSequence, Integer> sorted_map = new TreeMap<ProteinSequence, Integer>(vc);
        sorted_map.putAll(topSequences);
        List<ProteinSequence> list = new ArrayList<ProteinSequence>(sorted_map.keySet());


        //Start the multialignment
        log.info("Performnig multialignment");
        log.info("__________________________");
        Profile<ProteinSequence, AminoAcidCompound> profile = Alignments.getMultipleSequenceAlignment(list);
        ConcurrencyTools.shutdown();
        log.info(profile);

        FSDataOutputStream out = fs.create(new Path(output + "/report"));
        BufferedWriter bout = new BufferedWriter(new OutputStreamWriter(out));
        bout.append(profile.toString());
        bout.close();
    }
}
